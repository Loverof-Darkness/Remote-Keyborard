package com.loverofdarkness.remotekeyboard

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var typeField: RemoteEditText
    private var paired: List<BluetoothDevice> = emptyList()
    private var hid: ClassicHid? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        requestBluetoothPermissions()
        buildUi()
    }

    private fun requestBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) loadPairedDevices()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "Remote Keyboard"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            text = "● Disconnected"
            textSize = 16f
            setTextColor(0xFFFF5555.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }
        root.addView(status)
        deviceSpinner = Spinner(this)
        root.addView(deviceSpinner)
        root.addView(Button(this).apply { text = "Refresh Paired Devices"; setOnClickListener { loadPairedDevices() } })
        root.addView(Button(this).apply { text = "Connect"; setOnClickListener { connectSelected() } })
        root.addView(Button(this).apply { text = "Disconnect"; setOnClickListener { hid?.disconnect() } })
        root.addView(Button(this).apply {
            text = "Line Break ↵"
            setOnClickListener { if (hid?.isConnected() == true) sendKey(HidKeyMapper.ENTER) }
        })
        root.addView(Button(this).apply {
            text = "Clear Text"
            setOnClickListener { typeField.setText(""); typeField.setSelection(0); typeField.requestFocus() }
        })

        typeField = RemoteEditText(this) { token ->
            if (hid?.isConnected() != true) return@RemoteEditText
            when {
                token == "\b" -> sendKey(HidKeyMapper.BACKSPACE)
                token.startsWith("\u0000") -> token.substring(1).toIntOrNull()?.let(::sendKey)
                token == "\n" -> sendKey(HidKeyMapper.ENTER)
                else -> sendText(token)
            }
        }.apply {
            hint = "Tap here and type to your laptop"
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF888888.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xFF181818.toInt())
        }
        root.addView(typeField, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 24 })
        setContentView(root)
        loadPairedDevices()
    }

    private fun loadPairedDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (android.os.Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        try {
            paired = adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
            deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, paired.map { "${it.name ?: "Unknown"} • ${it.address}" })
            if (paired.isEmpty()) status.text = "● No paired Bluetooth devices"
        } catch (_: SecurityException) { status.text = "● Bluetooth permission required" }
    }

    private fun connectSelected() {
        val device = paired.getOrNull(deviceSpinner.selectedItemPosition) ?: return
        if (hid == null) {
            hid = ClassicHid.create(this) { connected, name ->
                runOnUiThread {
                    status.text = if (connected) "● Connected: ${name ?: "Laptop"}" else "● Disconnected"
                    status.setTextColor(if (connected) 0xFF55FF88.toInt() else 0xFFFF5555.toInt())
                }
            }
            hid?.start()
        }
        hid?.connect(device)
    }

    private fun sendText(text: String) { text.forEach { sendChar(it) } }
    private fun sendChar(c: Char): Boolean { val mapping = HidKeyMapper.map(c) ?: return false; return sendKey(mapping.usage, mapping.modifier) }
    private fun sendKey(usage: Int, modifier: Int = 0): Boolean {
        if (hid?.send(modifier, usage) != true) return false
        hid?.send(0, 0)
        return true
    }

    private fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val usage = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> HidKeyMapper.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> HidKeyMapper.DELETE
            KeyEvent.KEYCODE_ENTER -> HidKeyMapper.ENTER
            KeyEvent.KEYCODE_TAB -> HidKeyMapper.TAB
            KeyEvent.KEYCODE_ESCAPE -> HidKeyMapper.ESC
            KeyEvent.KEYCODE_DPAD_RIGHT -> HidKeyMapper.RIGHT
            KeyEvent.KEYCODE_DPAD_LEFT -> HidKeyMapper.LEFT
            KeyEvent.KEYCODE_DPAD_DOWN -> HidKeyMapper.DOWN
            KeyEvent.KEYCODE_DPAD_UP -> HidKeyMapper.UP
            KeyEvent.KEYCODE_MOVE_HOME -> HidKeyMapper.HOME
            KeyEvent.KEYCODE_MOVE_END -> HidKeyMapper.END
            KeyEvent.KEYCODE_PAGE_UP -> HidKeyMapper.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> HidKeyMapper.PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> HidKeyMapper.INSERT
            KeyEvent.KEYCODE_CAPS_LOCK -> HidKeyMapper.CAPS_LOCK
            KeyEvent.KEYCODE_SYSRQ -> HidKeyMapper.PRINT_SCREEN
            KeyEvent.KEYCODE_SCROLL_LOCK -> HidKeyMapper.SCROLL_LOCK
            KeyEvent.KEYCODE_BREAK -> HidKeyMapper.PAUSE
            else -> return false
        }
        return sendKey(usage)
    }

    override fun onDestroy() { hid?.close(); hid = null; super.onDestroy() }

    private inner class RemoteEditText(context: Context, private val emit: (String) -> Unit) : EditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            val base = super.onCreateInputConnection(outAttrs)
            return object : InputConnectionWrapper(base, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    text?.toString()?.takeIf { it.isNotEmpty() }?.let { committed -> emit(committed.replace("\r", "\n")) }
                    return super.commitText(text, newCursorPosition)
                }
                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    if (beforeLength > 0) repeat(beforeLength.coerceAtMost(32)) { emit("\b") }
                    if (afterLength > 0) repeat(afterLength.coerceAtMost(32)) { emit("\u0000${HidKeyMapper.DELETE}") }
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }
                override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                    if (beforeLength > 0) repeat(beforeLength.coerceAtMost(32)) { emit("\b") }
                    if (afterLength > 0) repeat(afterLength.coerceAtMost(32)) { emit("\u0000${HidKeyMapper.DELETE}") }
                    return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                }
                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    val isShiftEnter = event.keyCode == KeyEvent.KEYCODE_ENTER && event.isShiftPressed
                    if (isShiftEnter && event.action == KeyEvent.ACTION_DOWN) return super.sendKeyEvent(event)
                    val handled = sendKeyEventToLaptop(event)
                    val local = super.sendKeyEvent(event)
                    return handled || local
                }
                override fun performEditorAction(actionCode: Int): Boolean {
                    if (actionCode == EditorInfo.IME_ACTION_DONE || actionCode == EditorInfo.IME_ACTION_GO || actionCode == EditorInfo.IME_ACTION_NEXT || actionCode == EditorInfo.IME_ACTION_SEND || actionCode == EditorInfo.IME_ACTION_SEARCH) sendKey(HidKeyMapper.ENTER)
                    return super.performEditorAction(actionCode)
                }
                override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean {
                    text?.text?.toString()?.takeIf { it.isNotEmpty() }?.let(emit)
                    return super.commitCompletion(text)
                }
            }
        }
        private fun sendKeyEventToLaptop(event: KeyEvent): Boolean {
            if (hid?.isConnected() != true) return false
            return this@MainActivity.sendKeyEvent(event)
        }
    }
    companion object { private const val REQUEST_BLUETOOTH = 77 }
}
