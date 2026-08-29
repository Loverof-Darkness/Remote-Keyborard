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
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
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
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val missing = needed.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 77)
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.BLACK)
        }

        val title = TextView(this).apply {
            text = "Remote Keyboard"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

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

        val refresh = Button(this).apply {
            text = "Refresh Paired Devices"
            setOnClickListener { loadPairedDevices() }
        }
        root.addView(refresh, LinearLayout.LayoutParams(-1, -2))

        val connect = Button(this).apply {
            text = "Connect"
            setOnClickListener { connectSelected() }
        }
        root.addView(connect, LinearLayout.LayoutParams(-1, -2))

        val disconnect = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { hid?.disconnect() }
        }
        root.addView(disconnect, LinearLayout.LayoutParams(-1, -2))

        typeField = RemoteEditText(this) { text ->
            if (hid?.isConnected() == true) {
                sendText(text)
            }
        }.apply {
            hint = "Tap here and type to your laptop"
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF888888.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xFF181818.toInt())
        }
        root.addView(typeField, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = 24
        })

        setContentView(root)
        loadPairedDevices()
    }

    private fun loadPairedDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        paired = adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
        val labels = paired.map { "${it.name ?: "Unknown"} • ${it.address}" }
        deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
    }

    private fun connectSelected() {
        if (paired.isEmpty()) return
        val device = paired.getOrNull(deviceSpinner.selectedItemPosition) ?: return
        if (hid == null) {
            hid = ClassicHid.create(this) { connected, name ->
                runOnUiThread {
                    status.text = if (connected) {
                        "● Connected: ${name ?: "Laptop"}"
                    } else {
                        "● Disconnected"
                    }
                    status.setTextColor(
                        if (connected) 0xFF55FF88.toInt() else 0xFFFF5555.toInt()
                    )
                }
            }
            hid?.start()
        }
        hid?.connect(device)
    }

    private fun sendText(text: String) {
        text.forEach { c ->
            if (!sendChar(c)) {
                // Unsupported Unicode (emoji, CJK, etc.) is intentionally not
                // converted into a wrong key. The native field can still show it.
            }
        }
    }

    private fun sendChar(c: Char): Boolean {
        val mapping = HidKeyMapper.map(c) ?: return false
        if (!hid?.send(mapping.modifier, mapping.usage).orFalse()) return false
        hid?.send(0, 0)
        return true
    }

    private fun sendBackspace(count: Int) {
        repeat(count.coerceAtMost(32)) {
            if (hid?.send(0, HidKeyMapper.BACKSPACE).orFalse()) {
                hid?.send(0, 0)
            }
        }
    }

    private fun sendSpecialKey(usage: Int) {
        if (hid?.send(0, usage).orFalse()) hid?.send(0, 0)
    }

    private fun Boolean?.orFalse() = this == true

    override fun onDestroy() {
        hid?.close()
        hid = null
        super.onDestroy()
    }

    private class RemoteEditText(
        context: Context,
        private val onCommit: (String) -> Unit
    ) : EditText(context) {
        override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): InputConnection {
            val base = super.onCreateInputConnection(outAttrs)
            return object : InputConnectionWrapper(base, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    text?.toString()?.takeIf { it.isNotEmpty() }?.let(onCommit)
                    val result = super.commitText(text, newCursorPosition)
                    post { setText("") }
                    return result
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    if (beforeLength > 0) sendBackspaces(beforeLength)
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }

                override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                        when (event.keyCode) {
                            android.view.KeyEvent.KEYCODE_ENTER -> {
                                sendSpecial(HidKeyMapper.ENTER)
                                return true
                            }
                            android.view.KeyEvent.KEYCODE_TAB -> {
                                sendSpecial(HidKeyMapper.TAB)
                                return true
                            }
                        }
                    }
                    return super.sendKeyEvent(event)
                }
            }
        }

        private fun sendBackspaces(count: Int) {
            repeat(count.coerceAtMost(32)) {
                onCommit("\b")
            }
        }

        private fun sendSpecial(usage: Int) {
            onCommit("\u0000$usage")
        }
    }
}
