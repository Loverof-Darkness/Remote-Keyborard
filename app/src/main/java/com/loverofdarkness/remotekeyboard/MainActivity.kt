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
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var editor: EditText
    private lateinit var liveSwitch: Switch
    private lateinit var sendButton: Button
    private lateinit var searchButton: Button

    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private val sender = Executors.newSingleThreadExecutor()
    private val connectionEpoch = AtomicInteger(0)
    private var hid: ClassicHid? = null
    private var previousText = ""
    private var suppressChanges = false
    private var receiverRegistered = false

    private data class Stroke(val modifier: Int, val usage: Int)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtraCompat() ?: return
                    devices[device.address] = device
                    refreshDeviceList()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    searchButton.isEnabled = true
                    searchButton.text = "Search Bluetooth devices"
                    status.text = if (devices.isEmpty()) "No Bluetooth devices found" else "Select a paired laptop and connect"
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtraCompat() ?: return
                    devices[device.address] = device
                    refreshDeviceList()
                    if (device.bondState == BluetoothDevice.BOND_BONDED) status.text = "Paired — ready to connect"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestBluetoothPermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "Remote Keyboard"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            text = "Preparing Bluetooth…"
            textSize = 15f
            setTextColor(0xFFFFAA55.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(status)
        deviceSpinner = Spinner(this)
        root.addView(deviceSpinner)
        searchButton = addButton(root, "Search Bluetooth devices") { startSearch() }
        addButton(root, "Make phone discoverable") { makeDiscoverable() }
        addButton(root, "Connect selected") { connectSelected() }
        addButton(root, "Disconnect") { hid?.disconnect() }
        liveSwitch = Switch(this).apply {
            text = "Live typing"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        root.addView(liveSwitch)
        root.addView(TextView(this).apply {
            text = "Live mirrors the current buffer. After moving the laptop cursor, tap New buffer. Buffered mode lets you compose first, then Send. US-layout ASCII only."
            textSize = 13f
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, dp(4), 0, dp(8))
        })
        editor = EditText(this).apply {
            hint = "Tap here to open your native keyboard"
            textSize = 18f
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF888888.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFF181818.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(InputFilter.LengthFilter(1024))
            isEnabled = false
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        sendButton = addButton(root, "Send buffered text") { sendBufferedText() }
        sendButton.isEnabled = false
        addButton(root, "New buffer") { clearLocalBuffer() }
        addKeyRow(root, listOf("Enter" to HidKeyMapper.ENTER, "Backspace" to HidKeyMapper.BACKSPACE, "Tab" to HidKeyMapper.TAB, "Esc" to HidKeyMapper.ESC))
        addKeyRow(root, listOf("←" to HidKeyMapper.LEFT, "↑" to HidKeyMapper.UP, "↓" to HidKeyMapper.DOWN, "→" to HidKeyMapper.RIGHT))

        liveSwitch.setOnCheckedChangeListener { _, checked ->
            clearLocalBuffer()
            sendButton.isEnabled = !checked && hid?.isConnected() == true
        }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressChanges || !liveSwitch.isChecked || hid?.isConnected() != true) return
                val next = s.toString()
                if (!isSupported(next)) {
                    restorePreviousText()
                    status.text = "Only US-layout ASCII text is supported"
                    return
                }
                var common = 0
                while (common < previousText.length && common < next.length && previousText[common] == next[common]) common++
                val strokes = ArrayList<Stroke>(previousText.length - common + next.length - common)
                repeat(previousText.length - common) { strokes.add(Stroke(0, HidKeyMapper.BACKSPACE)) }
                next.substring(common).forEach { c -> strokes.add(strokeFor(c)!!) }
                if (queueStrokes(strokes)) previousText = next else restorePreviousText()
            }
        })

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun requestBluetoothPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) startBluetooth() else requestPermissions(permissions.toTypedArray(), REQUEST_BLUETOOTH)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        val bluetoothGranted = Build.VERSION.SDK_INT < 31 || (
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        )
        if (bluetoothGranted) startBluetooth() else status.text = "Nearby devices permissions are required"
    }

    private fun startBluetooth() {
        val a = adapter ?: run { status.text = "This device does not support Bluetooth"; return }
        if (!a.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            status.text = "Enable Bluetooth, then retry"
            return
        }
        loadKnownDevices()
        if (hid == null) {
            hid = ClassicHid.create(this) { connected, name ->
                runOnUiThread {
                    connectionEpoch.incrementAndGet()
                    clearLocalBuffer()
                    editor.isEnabled = connected
                    sendButton.isEnabled = connected && !liveSwitch.isChecked
                    status.setTextColor(if (connected) 0xFF55FF88.toInt() else 0xFFFFAA55.toInt())
                    status.text = if (connected) "Connected: ${name ?: "Bluetooth host"}" else "Disconnected"
                    if (connected) ConnectionNotification.show(this, name ?: "Bluetooth host") else ConnectionNotification.clear(this)
                }
            }
            hid?.start()
        }
    }

    private fun makeDiscoverable() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermissions(); return
        }
        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        })
    }

    private fun loadKnownDevices() {
        val a = adapter ?: return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        try {
            devices.clear()
            a.bondedDevices.forEach { devices[it.address] = it }
            refreshDeviceList()
            if (devices.isEmpty()) status.text = "No paired devices — make the phone discoverable and pair from laptop"
        } catch (_: SecurityException) { status.text = "Bluetooth permission required" }
    }

    private fun startSearch() {
        val a = adapter ?: return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermissions(); return
        }
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            loadKnownDevices()
            searchButton.isEnabled = false
            searchButton.text = "Searching…"
            status.text = "Searching nearby Bluetooth devices…"
            a.startDiscovery()
        } catch (t: Throwable) {
            searchButton.isEnabled = true
            searchButton.text = "Search Bluetooth devices"
            status.text = "Bluetooth search failed: ${t.javaClass.simpleName}"
        }
    }

    private fun refreshDeviceList() {
        if (!::deviceSpinner.isInitialized) return
        val list = devices.values.sortedWith(
            compareByDescending<BluetoothDevice> { it.bondState == BluetoothDevice.BOND_BONDED }
                .thenBy { safeName(it) }
        )
        val labels = list.map { d -> "${safeName(d)} • ${if (d.bondState == BluetoothDevice.BOND_BONDED) "Paired" else "Not paired"}" }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        deviceSpinner.tag = list
    }

    @Suppress("UNCHECKED_CAST")
    private fun connectSelected() {
        val list = deviceSpinner.tag as? List<BluetoothDevice> ?: emptyList()
        val device = list.getOrNull(deviceSpinner.selectedItemPosition) ?: run { status.text = "Select a Bluetooth device first"; return }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            status.text = "Pair this device first"
            try { device.createBond() } catch (t: Throwable) { status.text = "Pairing failed: ${t.javaClass.simpleName}" }
            return
        }
        if (hid == null) startBluetooth()
        connectionEpoch.incrementAndGet()
        clearLocalBuffer()
        status.text = "Connecting to ${safeName(device)}…"
        hid?.connect(device)
    }

    private fun sendBufferedText() {
        val text = editor.text.toString()
        if (text.isEmpty()) return
        if (!isSupported(text)) { status.text = "Unsupported characters — use US-layout ASCII"; return }
        if (queueStrokes(text.map { strokeFor(it)!! })) clearLocalBuffer()
    }

    private fun queueStrokes(strokes: List<Stroke>): Boolean {
        if (strokes.isEmpty()) return true
        val service = hid ?: run { status.text = "Connect a laptop first"; return false }
        if (!service.isConnected()) { status.text = "Connect a laptop first"; return false }
        val epoch = connectionEpoch.get()
        sender.execute {
            try {
                for (stroke in strokes) {
                    if (connectionEpoch.get() != epoch) return@execute
                    check(service.send(stroke.modifier, stroke.usage)) { "Key press rejected" }
                    Thread.sleep(10)
                    check(service.send(0, 0)) { "Key release rejected" }
                    Thread.sleep(10)
                }
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                if (connectionEpoch.compareAndSet(epoch, epoch + 1)) runOnUiThread {
                    clearLocalBuffer(); editor.isEnabled = false; sendButton.isEnabled = false
                    status.text = "Sending stopped: ${e.message ?: "HID error"}"
                    ConnectionNotification.clear(this)
                }
            }
        }
        return true
    }

    private fun clearLocalBuffer() {
        suppressChanges = true
        editor.setText("")
        previousText = ""
        suppressChanges = false
    }

    private fun restorePreviousText() {
        suppressChanges = true
        editor.setText(previousText)
        editor.setSelection(editor.length())
        suppressChanges = false
    }

    private fun isSupported(text: String): Boolean = text.all { strokeFor(it) != null }
    private fun strokeFor(c: Char): Stroke? = when (c) {
        '\n' -> Stroke(0, HidKeyMapper.ENTER)
        '\t' -> Stroke(0, HidKeyMapper.TAB)
        else -> HidKeyMapper.map(c)?.let { Stroke(it.modifier, it.usage) }
    }

    private fun addKeyRow(parent: LinearLayout, keys: List<Pair<String, Int>>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.forEach { (label, usage) ->
            val button = Button(this).apply {
                text = label
                setOnClickListener { if (queueStrokes(listOf(Stroke(0, usage)))) clearLocalBuffer() }
            }
            row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        parent.addView(row)
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit): Button {
        val button = Button(this).apply { text = label; setOnClickListener { action() } }
        parent.addView(button)
        return button
    }

    private fun safeName(device: BluetoothDevice): String = try { device.name?.takeIf { it.isNotBlank() } ?: device.address } catch (_: Throwable) { "Bluetooth device" }

    override fun onStart() {
        super.onStart()
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    override fun onStop() {
        try { adapter?.cancelDiscovery() } catch (_: Throwable) {}
        if (receiverRegistered) {
            try { unregisterReceiver(receiver) } catch (_: Throwable) {}
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        connectionEpoch.incrementAndGet()
        sender.shutdownNow()
        try { adapter?.cancelDiscovery() } catch (_: Throwable) {}
        hid?.close()
        hid = null
        ConnectionNotification.clear(this)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val REQUEST_BLUETOOTH = 77 }
}
