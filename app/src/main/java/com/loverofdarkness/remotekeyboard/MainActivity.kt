package com.loverofdarkness.remotekeyboard

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var typeField: RemoteEditText
    private lateinit var searchButton: Button
    private var devices = linkedMapOf<String, BluetoothDevice>()
    private var hid: ClassicHid? = null
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                try { devices[device.address] = device } catch (_: SecurityException) {}
                refreshDeviceList()
            } else if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                searchButton.isEnabled = true
                searchButton.text = "🔍 Search Bluetooth Devices"
                status.text = if (devices.isEmpty()) "● No devices found" else "● Select a device and Connect"
            } else if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (device != null) {
                    try { devices[device.address] = device } catch (_: SecurityException) {}
                    refreshDeviceList()
                    if (device.bondState == BluetoothDevice.BOND_BONDED) {
                        status.text = "● Paired — ready to connect"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
            else loadKnownDevices()
        } else loadKnownDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) loadKnownDevices()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24); setBackgroundColor(Color.BLACK) }
        root.addView(TextView(this).apply { text="Remote Keyboard"; textSize=26f; setTextColor(Color.WHITE); gravity=Gravity.CENTER })
        status = TextView(this).apply { text="● Bluetooth permission required"; textSize=16f; setTextColor(0xFFFFAA55.toInt()); gravity=Gravity.CENTER; setPadding(0,16,0,16) }
        root.addView(status)
        deviceSpinner = Spinner(this); root.addView(deviceSpinner)
        searchButton = Button(this).apply { text="🔍 Search Bluetooth Devices"; setOnClickListener { startSearch() } }
        root.addView(searchButton)
        root.addView(Button(this).apply { text="Connect Selected"; setOnClickListener { connectSelected() } })
        root.addView(Button(this).apply { text="Disconnect"; setOnClickListener { hid?.disconnect() } })
        typeField = RemoteEditText(this) { token ->
            if (hid?.isConnected() != true) return@RemoteEditText
            when { token == "\b" -> sendKey(HidKeyMapper.BACKSPACE); token.startsWith("\u0000") -> token.substring(1).toIntOrNull()?.let(::sendKey); token == "\n" -> sendKey(HidKeyMapper.ENTER); else -> sendText(token) }
        }.apply {
            hint="Tap here and type to your laptop"; textSize=18f; setTextColor(Color.WHITE); setHintTextColor(0xFF888888.toInt())
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI; minLines=4; gravity=Gravity.TOP or Gravity.START; setPadding(20,20,20,20); setBackgroundColor(0xFF181818.toInt())
        }
        root.addView(typeField, LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=16 })
        setContentView(root)
    }

    private fun loadKnownDevices() {
        val a = adapter ?: return
        if (android.os.Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        try {
            devices.clear(); a.bondedDevices.forEach { devices[it.address] = it }; refreshDeviceList()
            status.text = if (devices.isEmpty()) "● Tap Search to find Bluetooth devices" else "● Paired devices ready"
        } catch (_: SecurityException) { status.text="● Bluetooth permission required" }
    }

    private fun startSearch() {
        val a = adapter ?: return
        if (android.os.Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) { requestBluetoothPermissions(); return }
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            devices.clear(); a.bondedDevices.forEach { devices[it.address] = it }; refreshDeviceList()
            searchButton.isEnabled = false; searchButton.text = "🔎 Searching…"; status.text = "● Searching nearby Bluetooth devices…"
            a.startDiscovery()
        } catch (t: Throwable) { searchButton.isEnabled=true; status.text="● Bluetooth search failed: ${t.javaClass.simpleName}" }
    }

    private fun refreshDeviceList() {
        if (!::deviceSpinner.isInitialized) return
        val list = devices.values.toList().sortedWith(compareByDescending<BluetoothDevice> { it.bondState == BluetoothDevice.BOND_BONDED }.thenBy { try { it.name ?: it.address } catch (_: SecurityException) { it.address } })
        val labels = list.map { d -> try { "${d.name ?: "Unknown device"} • ${if (d.bondState == BluetoothDevice.BOND_BONDED) "Paired" else "Not paired"}" } catch (_: SecurityException) { "Bluetooth device" } }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        deviceSpinner.tag = list
    }

    @Suppress("UNCHECKED_CAST")
    private fun connectSelected() {
        val list = deviceSpinner.tag as? List<BluetoothDevice> ?: emptyList()
        val device = list.getOrNull(deviceSpinner.selectedItemPosition) ?: return
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            status.text = "● Pairing… confirm the Android Bluetooth prompt"
            try { device.createBond() } catch (t: Throwable) { status.text="● Pairing failed: ${t.javaClass.simpleName}" }
            return
        }
        connectDevice(device)
    }

    private fun connectDevice(device: BluetoothDevice) {
        if (adapter?.isDiscovering == true) adapter?.cancelDiscovery()
        if (hid == null) {
            hid = ClassicHid.create(this) { connected,name -> runOnUiThread {
                status.text = if (connected) "● Connected: ${name ?: "Bluetooth device"}" else "● Disconnected"
                status.setTextColor(if (connected) 0xFF55FF88.toInt() else 0xFFFF5555.toInt())
            }}
            hid?.start()
        }
        status.text="● Connecting to ${try { device.name ?: device.address } catch (_: SecurityException) { "device" }}…"
        hid?.connect(device)
    }

    private fun sendText(text:String) { text.forEach { sendChar(it) } }
    private fun sendChar(c:Char):Boolean { val m=HidKeyMapper.map(c) ?: return false; return sendKey(m.usage,m.modifier) }
    private fun sendKey(usage:Int,modifier:Int=0):Boolean { if(hid?.send(modifier,usage)!=true)return false; hid?.send(0,0); return true }

    private class RemoteEditText(context:Context, private val emit:(String)->Unit):EditText(context) {
        override fun onCreateInputConnection(outAttrs:EditorInfo):InputConnection {
            val base=super.onCreateInputConnection(outAttrs)
            return object:InputConnectionWrapper(base,false) {
                override fun commitText(text:CharSequence?,newCursorPosition:Int):Boolean { text?.toString()?.takeIf{it.isNotEmpty()}?.let{emit(it.replace("\r","\n"))}; return super.commitText(text,newCursorPosition) }
                override fun deleteSurroundingText(beforeLength:Int,afterLength:Int):Boolean { repeat(beforeLength.coerceAtMost(32)){emit("\b")}; return super.deleteSurroundingText(beforeLength,afterLength) }
                override fun performEditorAction(actionCode:Int):Boolean { if(actionCode!=EditorInfo.IME_ACTION_NONE)emit("\n"); return super.performEditorAction(actionCode) }
            }
        }
    }

    override fun onStart() { super.onStart(); val f=IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED) }; registerReceiver(receiver,f) }
    override fun onStop() { try { adapter?.cancelDiscovery() } catch (_:Throwable) {}; try { unregisterReceiver(receiver) } catch (_:Throwable) {}; super.onStop() }
    override fun onDestroy() { hid?.close(); hid=null; super.onDestroy() }

    companion object { private const val REQUEST_BLUETOOTH=77 }
}
