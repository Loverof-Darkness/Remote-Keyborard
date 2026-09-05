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

/** Bluetooth Classic HID device bridge. */
class ClassicHid private constructor(
    private val context: Context,
    private val onStateChanged: (Boolean, String?) -> Unit
) : BluetoothHidDevice.Callback() {
    private var device: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var lastHost: BluetoothDevice? = null
    private var registered = false

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE && proxy is BluetoothHidDevice) {
                device = proxy
                registerApp()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                registered = false
                device = null
                host = null
                onStateChanged(false, null)
            }
        }
    }

    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
        registered = isRegistered
        if (!isRegistered) {
            host = null
            onStateChanged(false, null)
            return
        }
        if (pluggedDevice != null) {
            host = pluggedDevice
            lastHost = pluggedDevice
        } else if (host == null && lastHost != null) {
            try { device?.connect(lastHost) } catch (t: Throwable) { Log.w(TAG, "reconnect failed", t) }
        }
        onStateChanged(host != null, host?.let(::safeName))
    }

    override fun onConnectionStateChanged(remote: BluetoothDevice, state: Int) {
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                host = remote
                lastHost = remote
            }
            BluetoothProfile.STATE_DISCONNECTED -> if (host == remote) host = null
        }
        onStateChanged(host != null, host?.let(::safeName))
    }

    override fun onGetReport(remote: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
        val payload = if ((id.toInt() and 0xFF) == 0xFE) HidReports.HID_INFORMATION else ReportBuilder.keyboardEmpty()
        try { device?.replyReport(remote, type, id, payload) } catch (t: Throwable) { Log.w(TAG, "replyReport failed", t) }
    }

    override fun onSetReport(remote: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) = Unit
    override fun onVirtualCableUnplug(remote: BluetoothDevice) {
        if (host == remote) host = null
        if (lastHost == remote) lastHost = null
        onStateChanged(false, null)
    }
    override fun onInterruptData(remote: BluetoothDevice, reportId: Byte, data: ByteArray) = Unit

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled || device != null) return
        try { adapter.getProfileProxy(context.applicationContext, serviceListener, BluetoothProfile.HID_DEVICE) }
        catch (t: Throwable) { Log.w(TAG, "getProfileProxy failed", t) }
    }

    private fun registerApp() {
        val d = device ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "BT HID Remote",
            "Bluetooth keyboard, mouse and presenter",
            "Md. Yaleed Haque",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidReports.COMBINED_DESCRIPTOR
        )
        val max = BluetoothHidDeviceAppQosSettings.MAX
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            max, max, max, max, max
        )
        try { d.registerApp(sdp, qos, qos, Executor { it.run() }, this) }
        catch (t: Throwable) { Log.w(TAG, "registerApp failed", t) }
    }

    fun connect(remote: BluetoothDevice) {
        lastHost = remote
        val d = device
        if (d == null) {
            start()
            return
        }
        if (!registered) return
        try { d.connect(remote) } catch (t: Throwable) { Log.w(TAG, "connect failed", t) }
    }

    fun disconnect() {
        val h = host ?: lastHost ?: return
        host = null
        lastHost = null
        try { device?.disconnect(h) } catch (t: Throwable) { Log.w(TAG, "disconnect failed", t) }
        onStateChanged(false, null)
    }

    fun currentHost(): BluetoothDevice? = host
    fun isRegistered(): Boolean = registered
    fun isConnected(): Boolean = host != null

    fun send(modifiers: Int, usage: Int): Boolean {
        val d = device ?: return false
        val h = host ?: return false
        return try { d.sendReport(h, HidReports.REPORT_ID_KEYBOARD, ReportBuilder.keyboard(modifiers, usage)) }
        catch (t: Throwable) { Log.w(TAG, "sendReport failed", t); false }
    }

    fun close() {
        host = null
        lastHost = null
        registered = false
        val d = device
        device = null
        try { d?.unregisterApp() } catch (_: Throwable) {}
        try { BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(BluetoothProfile.HID_DEVICE, d) } catch (_: Throwable) {}
    }

    private fun safeName(d: BluetoothDevice): String = try { d.name?.takeIf { it.isNotBlank() } ?: d.address }
    catch (_: Throwable) { "Bluetooth host" }

    companion object {
        private const val TAG = "RemoteKeyboardHid"
        fun create(context: Context, onStateChanged: (Boolean, String?) -> Unit) = ClassicHid(context.applicationContext, onStateChanged)
    }
}
