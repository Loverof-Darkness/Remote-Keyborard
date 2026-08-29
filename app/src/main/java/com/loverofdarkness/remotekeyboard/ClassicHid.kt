package com.loverofdarkness.remotekeyboard

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.Executor

class ClassicHid private constructor(
    context: Context,
    private val onStateChanged: (Boolean, String?) -> Unit
) : BluetoothHidDevice.Callback() {
    private val appContext = context.applicationContext
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var pendingHost: BluetoothDevice? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) return
            hid = proxy
            register()
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = null
                host = null
                onStateChanged(false, null)
            }
        }
    }

    fun start() {
        if (Build.VERSION.SDK_INT < 28 || adapter == null || !adapter.isEnabled) {
            onStateChanged(false, null)
            return
        }
        try {
            adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to open HID profile", t)
            onStateChanged(false, null)
        }
    }

    private fun register() {
        val service = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Remote Keyboard",
            "Native text-field Bluetooth keyboard",
            "Loverof-Darkness",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            HidReports.KEYBOARD_DESCRIPTOR
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 10, 600, 0xFFFFFFFF.toInt()
        )
        try {
            service.registerApp(sdp, qos, qos, Executor { it.run() }, this)
        } catch (t: Throwable) {
            Log.e(TAG, "registerApp failed", t)
            onStateChanged(false, null)
        }
    }

    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
        if (!registered) {
            host = null
            onStateChanged(false, null)
            return
        }
        pendingHost?.let {
            pendingHost = null
            connect(it)
        }
        pluggedDevice?.let {
            host = it
            onStateChanged(true, safeName(it))
        }
    }

    override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
        if (state == BluetoothProfile.STATE_CONNECTED) {
            host = device
            pendingHost = null
            onStateChanged(true, safeName(device))
        } else if (host == device) {
            host = null
            onStateChanged(false, null)
        }
    }

    override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
        try {
            hid?.replyReport(device, type, id, ReportBuilder.keyboardEmpty())
        } catch (t: Throwable) {
            Log.w(TAG, "replyReport failed", t)
        }
    }

    override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) = Unit
    override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) = Unit

    override fun onVirtualCableUnplug(device: BluetoothDevice) {
        if (host == device) host = null
        if (pendingHost == device) pendingHost = null
        onStateChanged(false, null)
    }

    fun connect(device: BluetoothDevice) {
        val service = hid
        if (service == null) {
            pendingHost = device
            return
        }
        try {
            if (!service.connect(device)) {
                pendingHost = device
                Log.w(TAG, "HID connect request was rejected")
            }
        } catch (t: Throwable) {
            pendingHost = device
            Log.e(TAG, "HID connect failed", t)
        }
    }

    fun disconnect() {
        pendingHost = null
        val current = host ?: return
        try { hid?.disconnect(current) } catch (t: Throwable) { Log.w(TAG, "disconnect failed", t) }
        host = null
        onStateChanged(false, null)
    }

    fun isConnected(): Boolean = host != null

    /**
     * AOSP BluetoothHidDevice.sendReport() takes an INT report ID.
     * The previous compile stub incorrectly declared BYTE, which compiled
     * but caused a runtime method-signature mismatch on real Android.
     */
    fun send(modifiers: Int, usage: Int): Boolean {
        val service = hid ?: return false
        val device = host ?: return false
        return try {
            val accepted = service.sendReport(
                device,
                HidReports.REPORT_ID_KEYBOARD,
                ReportBuilder.keyboard(modifiers, usage)
            )
            if (!accepted) Log.w(TAG, "sendReport rejected by HID service")
            accepted
        } catch (t: Throwable) {
            Log.e(TAG, "sendReport failed", t)
            false
        }
    }

    fun close() {
        pendingHost = null
        host = null
        try { hid?.unregisterApp() } catch (t: Throwable) { Log.w(TAG, "unregisterApp failed", t) }
        try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) } catch (_: Throwable) {}
        hid = null
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name?.takeIf { it.isNotBlank() } ?: device.address
    } catch (_: Throwable) { "Bluetooth host" }

    companion object {
        private const val TAG = "RemoteKeyboardHid"
        fun create(context: Context, onStateChanged: (Boolean, String?) -> Unit) =
            ClassicHid(context.applicationContext, onStateChanged)
    }
}
