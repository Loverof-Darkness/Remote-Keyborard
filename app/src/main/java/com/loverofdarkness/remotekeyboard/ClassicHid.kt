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
    private var reconnectAttempt = 0
    private var profileOpening = false
    private var connecting = false
    private var manualDisconnect = false
    private var reconnectGeneration = 0L

    private val connectionTimeout = object : Runnable {
        override fun run() {
            if (senderClosed || !connecting || host != null) return
            val target = pendingHost ?: return
            Log.w(TAG, "HID connection timeout for ${safeName(target)}; retrying")
            connecting = false
            try { hid?.disconnect(target) } catch (_: Throwable) {}
            scheduleReconnect(immediate = false)
        }
    }

    private val retryConnect = object : Runnable {
        override fun run() {
            if (senderClosed || manualDisconnect) return
            val target = pendingHost ?: lastHost ?: return
            pendingHost = target
            if (host != null) return

            if (adapter == null || !adapter.isEnabled) {
                scheduleReconnect(immediate = false)
                return
            }

            val service = hid
            if (service == null) {
                startProfile()
                scheduleReconnect(immediate = false)
                return
            }

            if (connecting) return
            connecting = true
            try {
                Log.i(TAG, "HID reconnect attempt ${reconnectAttempt + 1} to ${safeName(target)}")
                val accepted = service.connect(target)
                if (!accepted) {
                    connecting = false
                    scheduleReconnect(immediate = false)
                } else {
                    handler.removeCallbacks(connectionTimeout)
                    handler.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MS)
                }
            } catch (t: Throwable) {
                connecting = false
                Log.w(TAG, "HID connect failed", t)
                scheduleReconnect(immediate = false)
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) return
            profileOpening = false
            hid = proxy
            register()
            if (!manualDisconnect && pendingHost != null) {
                handler.removeCallbacks(retryConnect)
                handler.post(retryConnect)
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            profileOpening = false
            hid = null
            host = null
            connecting = false
            handler.removeCallbacks(connectionTimeout)
            clearQueuedReports()
            onStateChanged(false, null)
            if (!manualDisconnect && lastHost != null) {
                pendingHost = lastHost
                scheduleReconnect(immediate = true)
            }
        }
    }

    private val reconnectWatchdog = object : Runnable {
        override fun run() {
            if (senderClosed || manualDisconnect || host != null || pendingHost == null) return
            if (connecting) return
            scheduleReconnect(immediate = false)
        }
    }

    init {
        senderHandler.post(drainReports)
    }

    private val drainReports = object : Runnable {
        override fun run() {
            if (senderClosed) return
            val service = hid
            val device = host
            if (service == null || device == null) {
                reportQueue.clear()
                return
            }
            while (!senderClosed) {
                val report = reportQueue.poll() ?: break
                try {
                    if (!service.sendReport(device, HidReports.REPORT_ID_KEYBOARD, report)) {
                        Log.w(TAG, "sendReport rejected; dropping queued report")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "sendReport failed; dropping queued report", t)
                    break
                }
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
            scheduleReconnect(immediate = false)
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
            if (!manualDisconnect) scheduleReconnect(immediate = false)
            onStateChanged(false, null)
        }
    }

    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
        if (!registered) {
            host = null
            connecting = false
            handler.removeCallbacks(connectionTimeout)
            clearQueuedReports()
            onStateChanged(false, null)
            if (!manualDisconnect && lastHost != null) {
                pendingHost = lastHost
                scheduleReconnect(immediate = true)
            }
            return
        }

        pluggedDevice?.let {
            host = it
            lastHost = it
            pendingHost = null
            connecting = false
            reconnectAttempt = 0
            handler.removeCallbacks(connectionTimeout)
            handler.removeCallbacks(retryConnect)
            handler.removeCallbacks(reconnectWatchdog)
            onStateChanged(true, safeName(it))
            return
        }

        if (!manualDisconnect && pendingHost != null) {
            handler.removeCallbacks(retryConnect)
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
                reconnectAttempt = 0
                handler.removeCallbacks(connectionTimeout)
                handler.removeCallbacks(retryConnect)
                handler.removeCallbacks(reconnectWatchdog)
                onStateChanged(true, safeName(device))
            }
            BluetoothProfile.STATE_CONNECTING -> {
                if (!manualDisconnect && pendingHost == null) pendingHost = device
                connecting = true
                handler.removeCallbacks(connectionTimeout)
                handler.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MS)
            }
            BluetoothProfile.STATE_DISCONNECTING -> Unit
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (host == device) host = null
                if (pendingHost == null && lastHost == device) pendingHost = device
                connecting = false
                handler.removeCallbacks(connectionTimeout)
                clearQueuedReports()
                onStateChanged(false, null)
                if (!manualDisconnect && lastHost == device) scheduleReconnect(immediate = true)
            }
            else -> Unit
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
        if (lastHost == device) lastHost = null
        connecting = false
        handler.removeCallbacks(connectionTimeout)
        clearQueuedReports()
        handler.removeCallbacks(retryConnect)
        handler.removeCallbacks(reconnectWatchdog)
        onStateChanged(false, null)
    }

    fun connect(device: BluetoothDevice) {
        manualDisconnect = false
        if (host == device) return
        lastHost = device
        pendingHost = device
        reconnectAttempt = 0
        connecting = false
        reconnectGeneration++
        clearQueuedReports()
        handler.removeCallbacks(retryConnect)
        handler.removeCallbacks(reconnectWatchdog)
        handler.removeCallbacks(connectionTimeout)

        if (hid == null) {
            start()
            handler.post(retryConnect)
            return
        }
        handler.post(retryConnect)
    }

    fun disconnect() {
        manualDisconnect = true
        reconnectGeneration++
        handler.removeCallbacks(retryConnect)
        handler.removeCallbacks(reconnectWatchdog)
        handler.removeCallbacks(connectionTimeout)
        pendingHost = null
        lastHost = null
        reconnectAttempt = 0
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

    fun isConnected(): Boolean = host != null

    /** Queue a keyboard report for immediate background transmission. */
    fun send(modifiers: Int, usage: Int): Boolean {
        if (hid == null || host == null || senderClosed) return false
        val report = ReportBuilder.keyboard(modifiers, usage)
        val accepted = reportQueue.offer(report)
        if (accepted) senderHandler.post(drainReports)
        else Log.w(TAG, "HID report queue full; dropping input")
        return accepted
    }

    private fun scheduleReconnect(immediate: Boolean) {
        if (senderClosed || manualDisconnect || pendingHost == null) return
        handler.removeCallbacks(retryConnect)
        handler.removeCallbacks(reconnectWatchdog)
        if (immediate) {
            reconnectAttempt = 0
            handler.postDelayed(retryConnect, 150L)
            return
        }
        val delay = minOf(
            RECONNECT_MAX_DELAY_MS,
            RECONNECT_BASE_DELAY_MS * (1L shl minOf(reconnectAttempt, 4))
        )
        reconnectAttempt++
        handler.postDelayed(retryConnect, delay)
        handler.postDelayed(reconnectWatchdog, delay + CONNECTION_TIMEOUT_MS + 250L)
    }

    private fun clearQueuedReports() {
        reportQueue.clear()
    }

    fun close() {
        manualDisconnect = true
        reconnectGeneration++
        handler.removeCallbacksAndMessages(null)
        clearQueuedReports()
        pendingHost = null
        lastHost = null
        host = null
        profileOpening = false
        connecting = false
        try { hid?.unregisterApp() } catch (t: Throwable) { Log.w(TAG, "unregisterApp failed", t) }
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
        private const val CONNECTION_TIMEOUT_MS = 4500L
        private const val RECONNECT_BASE_DELAY_MS = 500L
        private const val RECONNECT_MAX_DELAY_MS = 5000L
        private const val MAX_QUEUED_REPORTS = 8192

        fun create(context: Context, onStateChanged: (Boolean, String?) -> Unit) =
            ClassicHid(context.applicationContext, onStateChanged)
    }
}
