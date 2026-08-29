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
    private var reconnectAttempts = 0
    private var profileOpening = false

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

    private val retryConnect = object : Runnable {
        override fun run() {
            val target = pendingHost ?: return
            if (host != null) return
            if (hid == null) {
                startProfile()
                handler.postDelayed(this, PROFILE_WAIT_MS)
                return
            }
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Log.w(TAG, "Giving up HID reconnect after $MAX_RECONNECT_ATTEMPTS attempts")
                pendingHost = null
                reconnectAttempts = 0
                onStateChanged(false, null)
                return
            }
            reconnectAttempts++
            try {
                Log.i(TAG, "HID reconnect attempt $reconnectAttempts")
                if (!hid!!.connect(target)) handler.postDelayed(this, RECONNECT_DELAY_MS)
            } catch (t: Throwable) {
                Log.w(TAG, "HID reconnect failed", t)
                handler.postDelayed(this, RECONNECT_DELAY_MS)
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) return
            profileOpening = false
            hid = proxy
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                profileOpening = false
                hid = null
                host = null
                clearQueuedReports()
                onStateChanged(false, null)
                lastHost?.let {
                    pendingHost = it
                    reconnectAttempts = 0
                    handler.postDelayed(retryConnect, RECONNECT_DELAY_MS)
                }
            }
        }
    }

    init {
        senderHandler.post(drainReports)
    }

    fun start() {
        if (Build.VERSION.SDK_INT < 28 || adapter == null || !adapter.isEnabled) {
            onStateChanged(false, null)
            return
        }
        if (hid != null || profileOpening) return
        startProfile()
    }

    private fun startProfile() {
        if (adapter == null || !adapter.isEnabled || profileOpening) return
        profileOpening = true
        try {
            adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (t: Throwable) {
            profileOpening = false
            Log.e(TAG, "Unable to open HID profile", t)
            handler.postDelayed(retryConnect, RECONNECT_DELAY_MS)
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
            clearQueuedReports()
            onStateChanged(false, null)
            lastHost?.let {
                pendingHost = it
                reconnectAttempts = 0
                handler.postDelayed(retryConnect, RECONNECT_DELAY_MS)
            }
            return
        }
        pluggedDevice?.let {
            host = it
            lastHost = it
            pendingHost = null
            reconnectAttempts = 0
            clearQueuedReports()
            handler.removeCallbacks(retryConnect)
            onStateChanged(true, safeName(it))
            return
        }
        pendingHost?.let {
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
                reconnectAttempts = 0
                clearQueuedReports()
                handler.removeCallbacks(retryConnect)
                onStateChanged(true, safeName(device))
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (host == device) host = null
                clearQueuedReports()
                onStateChanged(false, null)
                if (lastHost == device) {
                    pendingHost = device
                    reconnectAttempts = 0
                    handler.removeCallbacks(retryConnect)
                    handler.postDelayed(retryConnect, RECONNECT_DELAY_MS)
                }
            }
            BluetoothProfile.STATE_CONNECTING,
            BluetoothProfile.STATE_DISCONNECTING -> Unit
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
        clearQueuedReports()
        handler.removeCallbacks(retryConnect)
        onStateChanged(false, null)
    }

    fun connect(device: BluetoothDevice) {
        if (host == device) return
        lastHost = device
        pendingHost = device
        reconnectAttempts = 0
        clearQueuedReports()
        handler.removeCallbacks(retryConnect)

        if (hid == null) {
            start()
            handler.postDelayed(retryConnect, PROFILE_WAIT_MS)
            return
        }
        tryConnectNow()
    }

    private fun tryConnectNow() {
        val target = pendingHost ?: return
        val service = hid
        if (service == null) {
            handler.postDelayed(retryConnect, PROFILE_WAIT_MS)
            return
        }
        try {
            if (!service.connect(target)) handler.postDelayed(retryConnect, RECONNECT_DELAY_MS)
        } catch (t: Throwable) {
            Log.e(TAG, "HID connect failed", t)
            handler.postDelayed(retryConnect, RECONNECT_DELAY_MS)
        }
    }

    fun disconnect() {
        handler.removeCallbacks(retryConnect)
        pendingHost = null
        lastHost = null
        reconnectAttempts = 0
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

    /**
     * Queue a keyboard report for immediate background transmission. The
     * Bluetooth HID API sends each report over its interrupt channel, so the
     * queue preserves the required press/release ordering while keeping
     * binder/Bluetooth work off the UI and IME threads.
     */
    fun send(modifiers: Int, usage: Int): Boolean {
        if (hid == null || host == null || senderClosed) return false
        val report = ReportBuilder.keyboard(modifiers, usage)
        val accepted = reportQueue.offer(report)
        if (accepted) senderHandler.post(drainReports)
        else Log.w(TAG, "HID report queue full; dropping input")
        return accepted
    }

    private fun clearQueuedReports() {
        reportQueue.clear()
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        clearQueuedReports()
        pendingHost = null
        lastHost = null
        host = null
        profileOpening = false
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
        private const val PROFILE_WAIT_MS = 500L
        private const val RECONNECT_DELAY_MS = 800L
        private const val MAX_RECONNECT_ATTEMPTS = 6
        private const val MAX_QUEUED_REPORTS = 8192

        fun create(context: Context, onStateChanged: (Boolean, String?) -> Unit) =
            ClassicHid(context.applicationContext, onStateChanged)
    }
}
