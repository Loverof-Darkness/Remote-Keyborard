package com.loverofdarkness.remotekeyboard

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var typeField: EditText
    private var paired: List<BluetoothDevice> = emptyList()
    private var hid: ClassicHid? = null

    private val keyMap = mapOf(
        'a' to 0x04, 'b' to 0x05, 'c' to 0x06, 'd' to 0x07, 'e' to 0x08, 'f' to 0x09,
        'g' to 0x0A, 'h' to 0x0B, 'i' to 0x0C, 'j' to 0x0D, 'k' to 0x0E, 'l' to 0x0F,
        'm' to 0x10, 'n' to 0x11, 'o' to 0x12, 'p' to 0x13, 'q' to 0x14, 'r' to 0x15,
        's' to 0x16, 't' to 0x17, 'u' to 0x18, 'v' to 0x19, 'w' to 0x1A, 'x' to 0x1B,
        'y' to 0x1C, 'z' to 0x1D,
        '1' to 0x1E, '2' to 0x1F, '3' to 0x20, '4' to 0x21, '5' to 0x22,
        '6' to 0x23, '7' to 0x24, '8' to 0x25, '9' to 0x26, '0' to 0x27,
        ' ' to 0x2C, '\n' to 0x28, '\t' to 0x2B,
        '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31,
        ';' to 0x33, '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37, '/' to 0x38
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        if (android.os.Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = 0
        requestBluetoothPermissions()
        buildUi()
    }

    private fun requestBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 77)
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

        typeField = EditText(this).apply {
            hint = "Tap here and type to your laptop"
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF888888.toInt())
            setSingleLine(false)
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xFF181818.toInt())
        }
        root.addView(typeField, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 24 })

        typeField.addTextChangedListener(object : TextWatcher {
            private var internal = false
            private var sentLength = 0
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (internal) return
                val value = s?.toString() ?: return
                if (value.length < sentLength) {
                    val deletions = sentLength - value.length
                    repeat(deletions) { tap(0x2A) }
                    sentLength = value.length
                    return
                }
                if (value.length > sentLength) {
                    val added = value.substring(sentLength)
                    added.forEach { sendChar(it) }
                    sentLength = value.length
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        setContentView(root)
        loadPairedDevices()
    }

    private fun loadPairedDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return
        paired = adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
        val labels = paired.map { "${it.name ?: "Unknown"} • ${it.address}" }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private fun connectSelected() {
        if (paired.isEmpty()) return
        val device = paired[deviceSpinner.selectedItemPosition]
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

    private fun sendChar(c: Char) {
        val lower = c.lowercaseChar()
        val usage = keyMap[lower] ?: return
        val needsShift = c.isUpperCase() || c in "!@#$%^&*()_+{}|:\"~<>?"
        val modifier = if (needsShift) ReportBuilder.MOD_LEFT_SHIFT else 0
        if (!hid?.send(modifier, usage).orFalse()) return
        hid?.send(0, 0)
    }

    private fun tap(usage: Int) {
        hid?.send(0, usage)
        hid?.send(0, 0)
    }

    private fun Boolean?.orFalse() = this == true

    override fun onDestroy() {
        hid?.close()
        hid = null
        super.onDestroy()
    }
}
