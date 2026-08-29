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

class ClassicHid private constructor(
    context: Context,
    private val onStateChanged: (Boolean, String?) -> Unit
) : BluetoothHidDevice.Callback() {
    private val appContext = context.applicationContext
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val senderThread = HandlerThread("RemoteKeyboard-HidSender").apply { start() }
    private val senderHandler = Handler(senderThread.looper)
    private val reportQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED_REPORTS)

    @Volatile private var senderClosed = false
    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null

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
            if (adapter == null || !adapter.isEnabled) {
                scheduleReconnect()
                return
            }
            val service = hid
            if (service == null || !appRegistered) {
                if (service == null) startProfile()
                scheduleReconnect()
                return
            }
            if (connecting) return
            connecting = true
            try {
                Log.i(TAG, "HID connect attempt to ${safeName(target)}")
                if (!service.connect(target)) {
                    connecting = false
                    scheduleReconnect()
                }
            } catch (t: Throwable) {
                connecting = false
                Log.w(TAG, "HID connect failed", t)
                scheduleReconnect()
            }
        }
    }

    private val drainReports = object : Runnable {
        override fun run() {
            drainScheduled = false
            if (senderClosed) return
            val service = hid
            val device = host
            if (service == null || device == null || !appRegistered) {
                reportQueue.clear()
                return
            }
            val report = reportQueue.poll() ?: return
            try {
                val accepted = service.sendReport(device, HidReports.REPORT_ID_KEYBOARD, report)
                if (!accepted) Log.w(TAG, "sendReport rejected")
            } catch (t: Throwable) {
                Log.w(TAG, "sendReport failed", t)
            }
            // Never burst press/release packets. Give the Bluetooth HID
            // interrupt channel a small gap between reports.
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
            if (!manualDisconnect && lastHost != null) {
                pendingHost = lastHost
                scheduleReconnect(immediate = true)
            }
        }
    }

    fun start() {
        manualDisconnect = false
        if (Build.VERSION.SDK_INT < 28 || adapter == null || !adapter.isEnabled) {
            onStateChanged(false, null)
            return
        }
        if (hid != null || profileOpening) return
        startProfile()
    }

    private fun startProfile() {
        if (adapter == null || !adapter.isEnabled || profileOpening || senderClosed) return
        profileOpening = true
        try {
            adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (t: Throwable) {
            profileOpening = false
            Log.e(TAG, "Unable to open HID profile", t)
            scheduleReconnect()
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
        // Match the known-working reference: do not use tiny/custom QoS
        // buckets that can make some HID hosts reject the interrupt channel.
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
            if (!manualDisconnect) scheduleReconnect()
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
            if (!manualDisconnect && lastHost != null) {
                pendingHost = lastHost
                scheduleReconnect(immediate = true)
            }
            return
        }
        // pluggedDevice is the established HID virtual-cable host, not merely
        // the registration event. Treat it as connected only when supplied.
        if (pluggedDevice != null) {
            host = pluggedDevice
            lastHost = pluggedDevice
            pendingHost = null
            connecting = false
            handler.removeCallbacks(retryConnect)
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
                handler.removeCallbacks(retryConnect)
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
                if (!manualDisconnect && lastHost == device) pendingHost = device
                connecting = false
                clearQueuedReports()
                onStateChanged(false, null)
                if (!manualDisconnect && lastHost == device) scheduleReconnect(immediate = true)
            }
            BluetoothProfile.STATE_DISCONNECTING -> Unit
        }
    }

    override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
        // Android HID hosts may request the HID Information feature report
        // (0xFE) while establishing/refreshing the virtual cable. Failing to
        // answer GET_REPORT can make the host tear the HID link down.
        val payload = if ((id.toInt() and 0xFF) == 0xFE) {
            HidReports.HID_INFORMATION
        } else {
            ReportBuilder.keyboardEmpty()
        }
        try {
            hid?.replyReport(device, type, id, payload)
        } catch (t: Throwable) {
            Log.w(TAG, "replyReport failed", t)
        }
    }

    override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) = Unit
    override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) = Unit
    override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) = Unit

    override fun onVirtualCableUnplug(device: BluetoothDevice) {
        if (host == device) host = null
        if (pendingHost == device) pendingHost = null
        if (lastHost == device) lastHost = null
        connecting = false
        clearQueuedReports()
        handler.removeCallbacks(retryConnect)
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
        else Log.w(TAG, "HID report queue full")
        return accepted
    }

    private fun scheduleDrain() {
        if (senderClosed || drainScheduled) return
        drainScheduled = true
        senderHandler.post(drainReports)
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        if (senderClosed || manualDisconnect || pendingHost == null) return
        handler.removeCallbacks(retryConnect)
        handler.postDelayed(retryConnect, if (immediate) 300L else RECONNECT_DELAY_MS)
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
        private const val RECONNECT_DELAY_MS = 1500L
        private const val REPORT_GAP_MS = 12L
        private const val MAX_QUEUED_REPORTS = 1024

        fun create(context: Context, onStateChanged: (Boolean, String?) -> Unit) =
            ClassicHid(context.applicationContext, onStateChanged)
    }
}
