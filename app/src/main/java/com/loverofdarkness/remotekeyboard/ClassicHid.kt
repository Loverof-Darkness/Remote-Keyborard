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
        adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
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
        val executor = Executor { it.run() }
        try {
            service.registerApp(sdp, qos, qos, executor, this)
        } catch (t: Throwable) {
            Log.e(TAG, "registerApp failed", t)
        }
    }

    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
        if (!registered) {
            host = null
            onStateChanged(false, null)
            return
        }
        if (pluggedDevice != null) {
            host = pluggedDevice
            onStateChanged(true, safeName(pluggedDevice))
        }
    }

    override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
        if (state == BluetoothProfile.STATE_CONNECTED) {
            host = device
            onStateChanged(true, safeName(device))
        } else if (host == device) {
            host = null
            onStateChanged(false, null)
        }
    }

    override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
        val payload = HidReports.HID_INFORMATION.takeIf { (id.toInt() and 0xFF) == 0xFE }
            ?: ReportBuilder.keyboardEmpty()
        try { hid?.replyReport(device, type, id, payload) } catch (_: Throwable) {}
    }

    override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) = Unit
    override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) = Unit
    override fun onVirtualCableUnplug(device: BluetoothDevice) {
        host = null
        onStateChanged(false, null)
    }

    fun connect(device: BluetoothDevice) {
        try { hid?.connect(device) } catch (t: Throwable) { Log.e(TAG, "connect failed", t) }
    }

    fun disconnect() {
        val current = host ?: return
        host = null
        try { hid?.disconnect(current) } catch (_: Throwable) {}
        onStateChanged(false, null)
    }

    fun isConnected(): Boolean = host != null

    fun send(modifiers: Int, usage: Int): Boolean {
        val h = hid ?: return false
        val d = host ?: return false
        return try {
            h.sendReport(d, HidReports.REPORT_ID_KEYBOARD.toByte(), ReportBuilder.keyboard(modifiers, usage))
        } catch (_: Throwable) { false }
    }

    fun close() {
        host = null
        try { hid?.unregisterApp() } catch (_: Throwable) {}
        try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) } catch (_: Throwable) {}
        hid = null
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name?.takeIf { it.isNotBlank() } ?: device.address
    } catch (_: Throwable) { device.address }

    companion object {
        private const val TAG = "RemoteKeyboardHid"
        fun create(context: Context, onStateChanged: (Boolean, String?) -> Unit) =
            ClassicHid(context.applicationContext, onStateChanged)
    }
}
