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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

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
            val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BLUETOOTH)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) loadPairedDevices()
        else {
            status.text = "● Bluetooth permission required"
            status.setTextColor(0xFFFFAA33.toInt())
        }
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
        }, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            text = "● Disconnected"
            textSize = 16f
            setTextColor(0xFFFF5555.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        deviceSpinner = Spinner(this)
        root.addView(deviceSpinner, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Refresh Paired Devices"
            setOnClickListener { loadPairedDevices() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Connect"
            setOnClickListener { connectSelected() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Disconnect"
            setOnClickListener { hid?.disconnect() }
        }, LinearLayout.LayoutParams(-1, -2))

        typeField = RemoteEditText(this) { token ->
            if (hid?.isConnected() != true) return@RemoteEditText
            when {
                token == "\b" -> sendKey(HidKeyMapper.BACKSPACE)
                token.startsWith("\u0000") -> token.substring(1).toIntOrNull()?.let(::sendKey)
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
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            status.text = "● Bluetooth unavailable"
            status.setTextColor(0xFFFFAA33.toInt())
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        try {
            paired = adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
            deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, paired.map { "${it.name ?: "Unknown"} • ${it.address}" })
            if (paired.isEmpty()) {
                status.text = "● No paired Bluetooth devices"
                status.setTextColor(0xFFFFAA33.toInt())
            }
        } catch (_: SecurityException) {
            status.text = "● Bluetooth permission required"
            status.setTextColor(0xFFFFAA33.toInt())
        }
    }

    private fun connectSelected() {
        val device = paired.getOrNull(deviceSpinner.selectedItemPosition)
        if (device == null) {
            status.text = "● Select a paired device"
            status.setTextColor(0xFFFFAA33.toInt())
            return
        }
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

    private fun sendText(text: String) {
        text.forEach(::sendChar)
    }

    private fun sendChar(c: Char): Boolean {
        val mapping = HidKeyMapper.map(c) ?: return false
        return sendKey(mapping.usage, mapping.modifier)
    }

    private fun sendKey(usage: Int, modifier: Int = 0): Boolean {
        if (hid?.send(modifier, usage) != true) return false
        hid?.send(0, 0)
        return true
    }

    override fun onDestroy() {
        hid?.close()
        hid = null
        super.onDestroy()
    }

    private class RemoteEditText(context: Context, private val emit: (String) -> Unit) : EditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            val base = super.onCreateInputConnection(outAttrs)
            return object : InputConnectionWrapper(base, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    text?.toString()?.takeIf { it.isNotEmpty() }?.let(emit)
                    // Do not clear the field: clearing it here was breaking Gboard's IME pipeline.
                    return super.commitText(text, newCursorPosition)
                }

                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    // Keep composition inside the native EditText. Forward only the final commitText.
                    return super.setComposingText(text, newCursorPosition)
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    if (beforeLength > 0) repeat(beforeLength.coerceAtMost(32)) { emit("\b") }
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }

                override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                    if (beforeLength > 0) repeat(beforeLength.coerceAtMost(32)) { emit("\b") }
                    return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                }

                override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                        when (event.keyCode) {
                            android.view.KeyEvent.KEYCODE_ENTER -> { emit("\u0000${HidKeyMapper.ENTER}"); return true }
                            android.view.KeyEvent.KEYCODE_TAB -> { emit("\u0000${HidKeyMapper.TAB}"); return true }
                        }
                    }
                    return super.sendKeyEvent(event)
                }
            }
        }
    }

    companion object { private const val REQUEST_BLUETOOTH = 77 }
}
