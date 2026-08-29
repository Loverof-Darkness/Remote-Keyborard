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
    private var appRegistered = false
    private var connecting = false
    private var manualDisconnect = false

    private val retryConnect = object : Runnable {
        override fun run() {
            if (senderClosed || manualDisconnect || host != null) return

            val target = pendingHost ?: lastHost ?: return
            pendingHost = target

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
                Log.i(TAG, "HID connect attempt ${reconnectAttempt + 1} to ${safeName(target)}")
                val accepted = service.connect(target)
                if (!accepted) {
                    connecting = false
                    scheduleReconnect()
                } else {
                    reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(MAX_RECONNECT_ATTEMPTS)
                }
            } catch (t: Throwable) {
                connecting = false
                Log.w(TAG, "HID connect failed", t)
                scheduleReconnect()
            }
        }
    }

    private val reconnectWatchdog = object : Runnable {
        override fun run() {
            if (senderClosed || manualDisconnect || host != null || pendingHost == null) return
            if (!connecting) scheduleReconnect()
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

    private val drainReports = object : Runnable {
        override fun run() {
            if (senderClosed) return

            val service = hid
            val device = host
            if (service == null || device == null || !appRegistered) {
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

    init {
        senderHandler.post(drainReports)
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
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            10,
            600,
            0xFFFFFFFF.toInt()
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

        val connectedHost = pluggedDevice
        if (connectedHost != null) {
            host = connectedHost
            lastHost = connectedHost
            pendingHost = null
            connecting = false
            reconnectAttempt = 0
            handler.removeCallbacks(retryConnect)
            handler.removeCallbacks(reconnectWatchdog)
            onStateChanged(true, safeName(connectedHost))
            senderHandler.post(drainReports)
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
                handler.removeCallbacks(retryConnect)
                handler.removeCallbacks(reconnectWatchdog)
                onStateChanged(true, safeName(device))
                senderHandler.post(drainReports)
            }

            BluetoothProfile.STATE_CONNECTING -> {
                if (!manualDisconnect) {
                    pendingHost = pendingHost ?: lastHost ?: device
                    connecting = true
                }
            }

            BluetoothProfile.STATE_DISCONNECTING -> Unit

            BluetoothProfile.STATE_DISCONNECTED -> {
                if (host == device) host = null
                if (!manualDisconnect && lastHost == device) pendingHost = device
                connecting = false
                clearQueuedReports()
                onStateChanged(false, null)

                if (!manualDisconnect && lastHost == device) {
                    scheduleReconnect(immediate = true)
                }
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
        clearQueuedReports()
        handler.removeCallbacks(retryConnect)
        handler.removeCallbacks(reconnectWatchdog)
        onStateChanged(false, null)
    }

    fun connect(device: BluetoothDevice) {
        manualDisconnect = false

        if (host == device && appRegistered) return

        lastHost = device
        pendingHost = device
        reconnectAttempt = 0
        connecting = false
        clearQueuedReports()
        handler.removeCallbacks(retryConnect)
        handler.removeCallbacks(reconnectWatchdog)

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
        handler.removeCallbacks(reconnectWatchdog)
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

    fun isConnected(): Boolean = host != null && appRegistered

    /** Queue a keyboard report for immediate background transmission. */
    fun send(modifiers: Int, usage: Int): Boolean {
        if (!isConnected() || senderClosed) return false

        val report = ReportBuilder.keyboard(modifiers, usage)
        val accepted = reportQueue.offer(report)
        if (accepted) senderHandler.post(drainReports)
        else Log.w(TAG, "HID report queue full; dropping input")
        return accepted
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        if (senderClosed || manualDisconnect || pendingHost == null) return

        handler.removeCallbacks(retryConnect)
        handler.removeCallbacks(reconnectWatchdog)

        val delay = if (immediate) {
            250L
        } else {
            minOf(
                RECONNECT_MAX_DELAY_MS,
                RECONNECT_BASE_DELAY_MS * (1L shl minOf(reconnectAttempt, MAX_RECONNECT_ATTEMPTS))
            )
        }

        handler.postDelayed(retryConnect, delay)
        handler.postDelayed(reconnectWatchdog, delay + RECONNECT_WATCHDOG_EXTRA_MS)
    }

    private fun clearQueuedReports() {
        reportQueue.clear()
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

        try {
            hid?.unregisterApp()
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterApp failed", t)
        }

        try {
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        } catch (_: Throwable) {
        }

        hid = null
        senderClosed = true
        senderHandler.removeCallbacksAndMessages(null)
        senderThread.quitSafely()
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name?.takeIf { it.isNotBlank() } ?: device.address
    } catch (_: Throwable) {
        "Bluetooth host"
    }

    companion object {
        private const val TAG = "RemoteKeyboardHid"
        private const val RECONNECT_BASE_DELAY_MS = 500L
        private const val RECONNECT_MAX_DELAY_MS = 5000L
        private const val RECONNECT_WATCHDOG_EXTRA_MS = 1000L
        private const val MAX_RECONNECT_ATTEMPTS = 4
        private const val MAX_QUEUED_REPORTS = 8192

        fun create(
            context: Context,
            onStateChanged: (Boolean, String?) -> Unit
        ) = ClassicHid(context.applicationContext, onStateChanged)
    }
}
