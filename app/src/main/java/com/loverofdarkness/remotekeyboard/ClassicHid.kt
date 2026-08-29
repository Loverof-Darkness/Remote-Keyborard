package com.loverofdarkness.remotekeyboard

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue

/** Minimal Bluetooth Classic HID keyboard bridge. */
class ClassicHid private constructor(
    context: Context,
    private val onStateChanged: (Boolean, String?) -> Unit
) : BluetoothHidDevice.Callback() {
    private val appContext = context.applicationContext
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val senderThread = HandlerThread("RemoteKeyboard-HidSender").apply { start() }
    private val senderHandler = Handler(senderThread.looper)
    private val reportQueue = LinkedBlockingQueue<ByteArray>(256)

    @Volatile private var senderClosed = false
    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var protocolMode: Byte = BluetoothHidDevice.PROTOCOL_REPORT_MODE

    private var pendingHost: BluetoothDevice? = null
    private var lastHost: BluetoothDevice? = null
    private var profileOpening = false
    private var appRegistered = false
    private var connecting = false
    private var manualDisconnect = false
    private var drainScheduled = false

    private val retryConnect = object : Runnable {
        override fun run() {
            if (senderClosed || manualDisconnect || host != null) return
            val target = pendingHost ?: lastHost ?: return
            val service = hid
            if (adapter == null || !adapter.isEnabled || service == null || !appRegistered || connecting) return
            connecting = true
            try {
                Log.i(TAG, "HID connect attempt to ${safeName(target)}")
                if (!service.connect(target)) {
                    connecting = false
                }
            } catch (t: Throwable) {
                connecting = false
                Log.w(TAG, "HID connect failed", t)
            }
        }
    }

    private val drainReports = object : Runnable {
        override fun run() {
            drainScheduled = false
            if (senderClosed) return
            val service = hid ?: return
            val device = host ?: return
            if (!appRegistered) return
            val report = reportQueue.poll() ?: return
            try {
                // HID boot protocol has a fixed 8-byte keyboard report and no
                // Report ID. Report protocol uses the descriptor's ID 1.
                val reportId = currentReportId()
                val accepted = service.sendReport(device, reportId, report)
                if (!accepted) Log.w(TAG, "sendReport rejected id=$reportId mode=$protocolMode")
            } catch (t: Throwable) {
                Log.w(TAG, "sendReport failed id=${currentReportId()} mode=$protocolMode", t)
            }
            if (reportQueue.isNotEmpty() && host == device && appRegistered && !senderClosed) {
                drainScheduled = true
                senderHandler.postDelayed(this, REPORT_GAP_MS)
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) return
            profileOpening = false
            hid = proxy
            appRegistered = false
            protocolMode = BluetoothHidDevice.PROTOCOL_REPORT_MODE
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            profileOpening = false
            appRegistered = false
            hid = null
            host = null
            connecting = false
            clearQueuedReports()
            onStateChanged(false, null)
        }
    }

    fun start() {
        manualDisconnect = false
        if (Build.VERSION.SDK_INT < 28 || adapter == null || !adapter.isEnabled) {
            onStateChanged(false, null)
            return
        }
        if (hid != null || profileOpening) return
        profileOpening = true
        try {
            adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (t: Throwable) {
            profileOpening = false
            Log.e(TAG, "Unable to open HID profile", t)
            onStateChanged(false, null)
        }
    }

    private fun register() {
        val service = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Remote Keyboard",
            "Native Android keyboard bridge",
            "Loverof-Darkness",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            HidReports.KEYBOARD_DESCRIPTOR
        )
        val max = BluetoothHidDeviceAppQosSettings.MAX
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            max, max, max, max, max
        )
        try {
            service.registerApp(sdp, qos, qos, Executor { it.run() }, this)
        } catch (t: Throwable) {
            appRegistered = false
            Log.e(TAG, "registerApp failed", t)
            onStateChanged(false, null)
        }
    }

    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
        appRegistered = registered
        if (!registered) {
            host = null
            connecting = false
            clearQueuedReports()
            onStateChanged(false, null)
            return
        }
        if (pluggedDevice != null) {
            host = pluggedDevice
            lastHost = pluggedDevice
            pendingHost = null
            connecting = false
            onStateChanged(true, safeName(pluggedDevice))
            scheduleDrain()
        } else if (pendingHost != null && !manualDisconnect) {
            handler.post(retryConnect)
        }
    }

    override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                host = device
                lastHost = device
                pendingHost = null
                connecting = false
                onStateChanged(true, safeName(device))
                scheduleDrain()
            }
            BluetoothProfile.STATE_CONNECTING -> {
                if (!manualDisconnect) {
                    pendingHost = pendingHost ?: lastHost ?: device
                    connecting = true
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (host == device) host = null
                connecting = false
                clearQueuedReports()
                onStateChanged(false, null)
                // Deliberately do not auto-reconnect. A host rejecting the HID
                // protocol must not be hammered by a reconnect loop.
            }
            BluetoothProfile.STATE_DISCONNECTING -> Unit
        }
    }

    override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
        val requestedId = id.toInt() and 0xFF
        val payload = if (requestedId == 0xFE && protocolMode != BluetoothHidDevice.PROTOCOL_BOOT_MODE) {
            HidReports.HID_INFORMATION
        } else {
            ReportBuilder.keyboardEmpty()
        }
        try {
            hid?.replyReport(device, type, id, payload)
        } catch (t: Throwable) {
            Log.w(TAG, "replyReport failed id=$requestedId mode=$protocolMode", t)
        }
    }

    override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
        protocolMode = protocol
        Log.i(TAG, "HID protocol mode=${protocol.toInt() and 0xFF}")
    }

    override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) = Unit
    override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) = Unit

    override fun onVirtualCableUnplug(device: BluetoothDevice) {
        if (host == device) host = null
        if (pendingHost == device) pendingHost = null
        if (lastHost == device) lastHost = null
        connecting = false
        clearQueuedReports()
        onStateChanged(false, null)
    }

    fun connect(device: BluetoothDevice) {
        manualDisconnect = false
        if (host == device && appRegistered) return
        lastHost = device
        pendingHost = device
        connecting = false
        clearQueuedReports()
        handler.removeCallbacks(retryConnect)
        if (hid == null) {
            start()
            return
        }
        if (!appRegistered) {
            register()
            return
        }
        handler.post(retryConnect)
    }

    fun disconnect() {
        manualDisconnect = true
        handler.removeCallbacks(retryConnect)
        pendingHost = null
        lastHost = null
        connecting = false
        clearQueuedReports()
        val current = host
        if (current == null) {
            onStateChanged(false, null)
            return
        }
        try {
            hid?.disconnect(current)
        } catch (t: Throwable) {
            Log.w(TAG, "disconnect failed", t)
            host = null
            onStateChanged(false, null)
        }
    }

    fun isConnected(): Boolean = host != null && appRegistered

    fun send(modifiers: Int, usage: Int): Boolean {
        if (!isConnected() || senderClosed) return false
        val accepted = reportQueue.offer(ReportBuilder.keyboard(modifiers, usage))
        if (accepted) scheduleDrain()
        return accepted
    }

    private fun currentReportId(): Int =
        if (protocolMode == BluetoothHidDevice.PROTOCOL_BOOT_MODE) 0 else HidReports.REPORT_ID_KEYBOARD

    private fun scheduleDrain() {
        if (senderClosed || drainScheduled) return
        drainScheduled = true
        senderHandler.post(drainReports)
    }

    private fun clearQueuedReports() {
        reportQueue.clear()
        senderHandler.removeCallbacks(drainReports)
        drainScheduled = false
    }

    fun close() {
        manualDisconnect = true
        handler.removeCallbacksAndMessages(null)
        clearQueuedReports()
        pendingHost = null
        lastHost = null
        host = null
        profileOpening = false
        appRegistered = false
        connecting = false
        try { hid?.unregisterApp() } catch (_: Throwable) {}
        try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) } catch (_: Throwable) {}
        hid = null
        senderClosed = true
        senderHandler.removeCallbacksAndMessages(null)
        senderThread.quitSafely()
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name?.takeIf { it.isNotBlank() } ?: device.address
    } catch (_: Throwable) { "Bluetooth host" }

    companion object {
        private const val TAG = "RemoteKeyboardHid"
        private const val REPORT_GAP_MS = 12L

        fun create(context: Context, onStateChanged: (Boolean, String?) -> Unit) =
            ClassicHid(context.applicationContext, onStateChanged)
    }
}
