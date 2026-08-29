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
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue

class ClassicHid private constructor(
    context: Context,
    private val onStateChanged: (Boolean, String?) -> Unit
) : BluetoothHidDevice.Callback() {
    private val appContext = context.applicationContext
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val handler = Handler(android.os.Looper.getMainLooper())
    private val senderThread = HandlerThread("RemoteKeyboard-HidSender").apply { start() }
    private val senderHandler = Handler(senderThread.looper)
    private val reportQueue = LinkedBlockingQueue<ByteArray>(8192)

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

    private val drainReports = object : Runnable {
        override fun run() {
            if (senderClosed) return
            val service = hid ?: return
            val device = host ?: return
            if (!appRegistered) return
            val report = reportQueue.poll() ?: return
            try {
                if (service.sendReport(device, HidReports.REPORT_ID_KEYBOARD, report)) {
                    // Give the host a real release edge before the next report.
                    senderHandler.postDelayed(this, 25L)
                } else {
                    Log.w(TAG, "sendReport rejected")
                    senderHandler.post(this)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sendReport failed", t)
                senderHandler.post(this)
            }
        }
    }

    private val retryConnect = object : Runnable {
        override fun run() {
            if (senderClosed || manualDisconnect || host != null) return
            val target = pendingHost ?: lastHost ?: return
            pendingHost = target
            if (adapter == null || !adapter.isEnabled) { scheduleReconnect(); return }
            val service = hid
            if (service == null || !appRegistered) {
                if (service == null) startProfile()
                scheduleReconnect()
                return
            }
            if (connecting) return
            connecting = true
            try {
                val accepted = service.connect(target)
                if (!accepted) { connecting = false; scheduleReconnect() }
                else reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(4)
            } catch (t: Throwable) {
                connecting = false
                Log.w(TAG, "HID connect failed", t)
                scheduleReconnect()
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
            reportQueue.clear()
            onStateChanged(false, null)
            if (!manualDisconnect && lastHost != null) { pendingHost = lastHost; scheduleReconnect(true) }
        }
    }

    fun start() {
        manualDisconnect = false
        if (Build.VERSION.SDK_INT < 28 || adapter == null || !adapter.isEnabled) { onStateChanged(false, null); return }
        if (hid != null || profileOpening) return
        startProfile()
    }

    private fun startProfile() {
        if (adapter == null || !adapter.isEnabled || profileOpening || senderClosed) return
        profileOpening = true
        try { adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE) }
        catch (t: Throwable) { profileOpening = false; Log.e(TAG, "Unable to open HID profile", t); scheduleReconnect(); onStateChanged(false, null) }
    }

    private fun register() {
        val service = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Remote Keyboard", "Bluetooth keyboard", "Loverof-Darkness",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD, HidReports.KEYBOARD_DESCRIPTOR
        )
        val max = BluetoothHidDeviceAppQosSettings.MAX
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, max, max, max, max, max
        )
        try { service.registerApp(sdp, qos, qos, Executor { it.run() }, this) }
        catch (t: Throwable) { appRegistered = false; Log.e(TAG, "registerApp failed", t); scheduleReconnect(); onStateChanged(false, null) }
    }

    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
        appRegistered = registered
        if (!registered) {
            host = null; connecting = false; reportQueue.clear(); onStateChanged(false, null)
            if (!manualDisconnect && lastHost != null) { pendingHost = lastHost; scheduleReconnect(true) }
            return
        }
        pluggedDevice?.let {
            host = it; lastHost = it; pendingHost = null; connecting = false; reconnectAttempt = 0
            handler.removeCallbacks(retryConnect); onStateChanged(true, safeName(it)); senderHandler.post(drainReports); return
        }
        if (!manualDisconnect && pendingHost != null) { handler.removeCallbacks(retryConnect); handler.post(retryConnect) }
    }

    override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                host = device; lastHost = device; pendingHost = null; connecting = false; reconnectAttempt = 0
                handler.removeCallbacks(retryConnect); reportQueue.clear(); onStateChanged(true, safeName(device)); senderHandler.post(drainReports)
            }
            BluetoothProfile.STATE_CONNECTING -> if (!manualDisconnect) { pendingHost = pendingHost ?: lastHost ?: device; connecting = true }
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (host == device) host = null
                if (!manualDisconnect && lastHost == device) pendingHost = device
                connecting = false; reportQueue.clear(); onStateChanged(false, null)
                if (!manualDisconnect && lastHost == device) scheduleReconnect(true)
            }
        }
    }

    override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
        // Match the working reference: HID Information is a feature report.
        val payload = if ((id.toInt() and 0xFF) == 0xFE) HidReports.HID_INFORMATION else ReportBuilder.keyboardEmpty()
        try { hid?.replyReport(device, type, id, payload) } catch (t: Throwable) { Log.w(TAG, "replyReport failed", t) }
    }
    override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) = Unit
    override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) = Unit
    override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) = Unit
    override fun onVirtualCableUnplug(device: BluetoothDevice) {
        if (host == device) host = null
        if (pendingHost == device) pendingHost = null
        if (lastHost == device) lastHost = null
        connecting = false; reportQueue.clear(); handler.removeCallbacks(retryConnect); onStateChanged(false, null)
    }

    fun connect(device: BluetoothDevice) {
        manualDisconnect = false
        if (host == device && appRegistered) return
        lastHost = device; pendingHost = device; reconnectAttempt = 0; connecting = false; reportQueue.clear(); handler.removeCallbacks(retryConnect)
        if (hid == null) { start(); return }
        if (!appRegistered) { register(); return }
        handler.post(retryConnect)
    }

    fun disconnect() {
        manualDisconnect = true; handler.removeCallbacks(retryConnect); pendingHost = null; lastHost = null; connecting = false; reportQueue.clear()
        val current = host ?: run { onStateChanged(false, null); return }
        try { hid?.disconnect(current) } catch (_: Throwable) {}
        host = null; onStateChanged(false, null)
    }

    fun isConnected(): Boolean = host != null && appRegistered

    /** Enqueue press/release reports; never blast them back-to-back. */
    fun send(modifiers: Int, usage: Int): Boolean {
        if (!isConnected() || senderClosed) return false
        val accepted = reportQueue.offer(ReportBuilder.keyboard(modifiers, usage))
        if (!accepted) return false
        // Caller also queues the explicit empty report; each report is spaced 25ms.
        senderHandler.post(drainReports)
        return true
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        if (senderClosed || manualDisconnect || pendingHost == null) return
        handler.removeCallbacks(retryConnect)
        val delay = if (immediate) 250L else minOf(5000L, 500L * (1L shl minOf(reconnectAttempt, 4)))
        handler.postDelayed(retryConnect, delay)
    }

    fun close() {
        manualDisconnect = true; handler.removeCallbacksAndMessages(null); reportQueue.clear(); pendingHost = null; lastHost = null; host = null
        try { hid?.unregisterApp() } catch (_: Throwable) {}
        try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) } catch (_: Throwable) {}
        hid = null; senderClosed = true; senderHandler.removeCallbacksAndMessages(null); senderThread.quitSafely()
    }

    private fun safeName(device: BluetoothDevice): String = try { device.name?.takeIf { it.isNotBlank() } ?: device.address } catch (_: Throwable) { "Bluetooth host" }
    companion object {
        private const val TAG = "RemoteKeyboardHid"
        fun create(context: Context, onStateChanged: (Boolean, String?) -> Unit) = ClassicHid(context.applicationContext, onStateChanged)
    }
}
