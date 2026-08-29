package com.loverofdarkness.remotekeyboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var typeField: RemoteEditText
    private lateinit var customPanel: LinearLayout
    private lateinit var keyboardPanel: LinearLayout
    private var paired: List<android.bluetooth.BluetoothDevice> = emptyList()
    private var hid: ClassicHid? = null
    private val prefs by lazy { getSharedPreferences("remote_keyboard", MODE_PRIVATE) }
    private val repeatHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        requestBluetoothPermissions()
        requestNotificationPermission()
        buildUi()
    }

    private fun requestBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) loadPairedDevices()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24); setBackgroundColor(Color.BLACK) }
        root.addView(TextView(this).apply { text="Remote Keyboard"; textSize=26f; setTextColor(Color.WHITE); gravity=Gravity.CENTER })
        status = TextView(this).apply { text="● Disconnected"; textSize=16f; setTextColor(0xFFFF5555.toInt()); gravity=Gravity.CENTER; setPadding(0,16,0,16) }
        root.addView(status)
        deviceSpinner=Spinner(this); root.addView(deviceSpinner)
        root.addView(Button(this).apply { text="Quick Connect Last Device"; setOnClickListener{quickConnect()} })
        root.addView(Button(this).apply { text="Refresh Paired Devices"; setOnClickListener{loadPairedDevices()} })
        root.addView(Button(this).apply { text="Connect"; setOnClickListener{connectSelected()} })
        root.addView(Button(this).apply { text="Disconnect"; setOnClickListener{hid?.disconnect()} })

        val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        tabs.addView(Button(this).apply{text="Keyboard";setOnClickListener{keyboardPanel.visibility=View.VISIBLE;customPanel.visibility=View.GONE}},LinearLayout.LayoutParams(0,-2,1f))
        tabs.addView(Button(this).apply{text="Custom Keys";setOnClickListener{keyboardPanel.visibility=View.GONE;customPanel.visibility=View.VISIBLE}},LinearLayout.LayoutParams(0,-2,1f)); root.addView(tabs)

        keyboardPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val special=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("ESC" to HidKeyMapper.ESC,"TAB" to HidKeyMapper.TAB,"F1" to 0x3A,"F2" to 0x3B,"F3" to 0x3C,"F4" to 0x3D).forEach{(n,u)->special.addView(keyButton(n){sendKey(u)},LinearLayout.LayoutParams(0,-2,1f))}; keyboardPanel.addView(special)
        val special2=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("F5" to 0x3E,"F6" to 0x3F,"F7" to 0x40,"F8" to 0x41,"F9" to 0x42,"F10" to 0x43,"F11" to 0x44,"F12" to 0x45).forEach{(n,u)->special2.addView(keyButton(n){sendKey(u)},LinearLayout.LayoutParams(0,-2,1f))}; keyboardPanel.addView(special2)
        val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("HOME" to HidKeyMapper.HOME,"END" to HidKeyMapper.END,"PG↑" to HidKeyMapper.PAGE_UP,"PG↓" to HidKeyMapper.PAGE_DOWN,"INS" to HidKeyMapper.INSERT,"DEL" to HidKeyMapper.DELETE).forEach{(n,u)->nav.addView(keyButton(n){sendKey(u)},LinearLayout.LayoutParams(0,-2,1f))}; keyboardPanel.addView(nav)
        val arrows=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        listOf("←" to HidKeyMapper.LEFT,"↑" to HidKeyMapper.UP,"↓" to HidKeyMapper.DOWN,"→" to HidKeyMapper.RIGHT).forEach{(n,u)->arrows.addView(keyButton(n){sendKey(u)},LinearLayout.LayoutParams(0,-2,1f))}; keyboardPanel.addView(arrows)
        root.addView(keyboardPanel)

        customPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        customPanel.addView(Button(this).apply{text="+ Add Custom Key";setOnClickListener{showAddCustomDialog()}})
        customPanel.addView(Button(this).apply{text="Send Clipboard to Laptop";setOnClickListener{sendClipboard()}})
        loadCustomButtons(); root.addView(customPanel)

        root.addView(Button(this).apply{text="Line Break ↵";setOnClickListener{if(hid?.isConnected()==true)sendKey(HidKeyMapper.ENTER,HidKeyMapper.MODIFIER_LEFT_SHIFT)}})
        root.addView(Button(this).apply{text="Clear Text";setOnClickListener{typeField.setText("");typeField.setSelection(0);typeField.requestFocus()}})

        typeField=RemoteEditText(this){token->if(hid?.isConnected()!=true)return@RemoteEditText;when{token=="\b"->sendKey(HidKeyMapper.BACKSPACE);token.startsWith("\u0000")->token.substring(1).toIntOrNull()?.let(::sendKey);token=="\n"->sendKey(HidKeyMapper.ENTER);else->sendText(token)}}.apply{hint="Tap here and type to your laptop";textSize=18f;setTextColor(Color.WHITE);setHintTextColor(0xFF888888.toInt());inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI;minLines=4;gravity=Gravity.TOP or Gravity.START;setPadding(20,20,20,20);setBackgroundColor(0xFF181818.toInt())}
        root.addView(typeField,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=16});setContentView(root);loadPairedDevices()
    }

    private fun keyButton(text:String, action:()->Unit):Button {
        val button=Button(this).apply{this.text=text;setOnClickListener{if(hid?.isConnected()==true)action()}}
        var repeating=false
        button.setOnLongClickListener {
            if(hid?.isConnected()!=true)return@setOnLongClickListener false
            repeating=true
            fun repeat(){if(!repeating)return;action();repeatHandler.postDelayed(::repeat,90)}
            action();repeatHandler.postDelayed(::repeat,250)
            true
        }
        button.setOnTouchListener { _, event ->
            if(event.action==MotionEvent.ACTION_UP || event.action==MotionEvent.ACTION_CANCEL) { repeating=false;repeatHandler.removeCallbacksAndMessages(button) }
            false
        }
        return button
    }

    private fun showAddCustomDialog(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,8,24,0)}
        val name=EditText(this).apply{hint="Button name"}
        val sequence=EditText(this).apply{hint="CTRL+C, ALT+TAB, F5, etc.";inputType=InputType.TYPE_CLASS_TEXT}
        box.addView(name);box.addView(sequence)
        AlertDialog.Builder(this).setTitle("Add Custom Key").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save"){_,_->
            val n=name.text.toString().trim();val s=sequence.text.toString().trim();if(n.isNotEmpty()&&s.isNotEmpty()){val key="${n.replace("|","")}|${s.replace("|","")}";val old=prefs.getStringSet("keys",emptySet())!!.toMutableSet();old.add(key);prefs.edit().putStringSet("keys",old).apply();loadCustomButtons()}}
            .show()
    }

    private fun loadCustomButtons(){if(!::customPanel.isInitialized)return;while(customPanel.childCount>2)customPanel.removeViewAt(2);prefs.getStringSet("keys",emptySet())!!.toList().sorted().forEach{entry->val p=entry.split("|",limit=2);if(p.size==2){val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};row.addView(keyButton(p[0]){sendSequence(p[1])},LinearLayout.LayoutParams(0,-2,1f));row.addView(Button(this).apply{text="×";setOnClickListener{prefs.edit().putStringSet("keys",prefs.getStringSet("keys",emptySet())!!.filterNot{it==entry}.toSet()).apply();loadCustomButtons()}},LinearLayout.LayoutParams(-2,-2));customPanel.addView(row)}}}

    private fun sendSequence(sequence:String){
        if(hid?.isConnected()!=true)return
        sequence.split(";").map{it.trim()}.filter{it.isNotEmpty()}.forEach { action ->
            if(action.startsWith("TEXT(",true)&&action.endsWith(")")) sendText(action.substring(5,action.length-1))
            else {
                val tokens=action.split("+").map{it.trim()}.filter{it.isNotEmpty()}
                if(tokens.isEmpty()) return@forEach
                var modifier=0
                tokens.forEach { modifier=modifier or (HidKeyMapper.modifierFor(it)?:0) }
                val key=tokens.lastOrNull{HidKeyMapper.modifierFor(it)==null} ?: return@forEach
                val usage=HidKeyMapper.usageFor(key) ?: return@forEach
                sendKey(usage,modifier)
            }
        }
    }

    private fun sendClipboard(){
        if(hid?.isConnected()!=true)return
        val clipboard=getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text=clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if(text.isEmpty())return
        sendText(text)
    }

    private fun loadPairedDevices(){val adapter=android.bluetooth.BluetoothAdapter.getDefaultAdapter()?:return;if(android.os.Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;try{paired=adapter.bondedDevices.toList().sortedBy{it.name?:it.address};val labels=paired.map{"${it.name?:"Unknown"} • ${it.address}"};deviceSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,labels);val last=prefs.getString("last_address",null);val index=paired.indexOfFirst{it.address==last};if(index>=0)deviceSpinner.setSelection(index);if(paired.isEmpty())status.text="● No paired Bluetooth devices"}catch(_:SecurityException){status.text="● Bluetooth permission required"}}
    private fun quickConnect(){val last=prefs.getString("last_address",null);val device=paired.firstOrNull{it.address==last};if(device!=null)connectDevice(device)else status.text="● No remembered device"}
    private fun connectSelected(){paired.getOrNull(deviceSpinner.selectedItemPosition)?.let(::connectDevice)}
    private fun connectDevice(device:android.bluetooth.BluetoothDevice){prefs.edit().putString("last_address",device.address).apply();if(hid==null){hid=ClassicHid.create(this){connected,name->runOnUiThread{status.text=if(connected)"● Connected: ${name?:"Laptop"}" else "● Disconnected";status.setTextColor(if(connected)0xFF55FF88.toInt() else 0xFFFF5555.toInt());if(connected)ConnectionNotification.show(this,name?:"Laptop")else ConnectionNotification.clear(this)}};hid?.start()};hid?.connect(device)}
    private fun sendText(text:String){text.forEach{sendChar(it)}}
    private fun sendChar(c:Char):Boolean{val m=HidKeyMapper.map(c)?:return false;return sendKey(m.usage,m.modifier)}
    private fun sendKey(usage:Int,modifier:Int=0):Boolean{if(hid?.send(modifier,usage)!=true)return false;hid?.send(0,0);return true}
    private fun sendKeyEvent(event:KeyEvent):Boolean{if(event.action!=KeyEvent.ACTION_DOWN)return true;val usage=when(event.keyCode){KeyEvent.KEYCODE_DEL->HidKeyMapper.BACKSPACE;KeyEvent.KEYCODE_FORWARD_DEL->HidKeyMapper.DELETE;KeyEvent.KEYCODE_ENTER->HidKeyMapper.ENTER;KeyEvent.KEYCODE_TAB->HidKeyMapper.TAB;KeyEvent.KEYCODE_ESCAPE->HidKeyMapper.ESC;KeyEvent.KEYCODE_DPAD_RIGHT->HidKeyMapper.RIGHT;KeyEvent.KEYCODE_DPAD_LEFT->HidKeyMapper.LEFT;KeyEvent.KEYCODE_DPAD_DOWN->HidKeyMapper.DOWN;KeyEvent.KEYCODE_DPAD_UP->HidKeyMapper.UP;KeyEvent.KEYCODE_MOVE_HOME->HidKeyMapper.HOME;KeyEvent.KEYCODE_MOVE_END->HidKeyMapper.END;KeyEvent.KEYCODE_PAGE_UP->HidKeyMapper.PAGE_UP;KeyEvent.KEYCODE_PAGE_DOWN->HidKeyMapper.PAGE_DOWN;KeyEvent.KEYCODE_INSERT->HidKeyMapper.INSERT;KeyEvent.KEYCODE_CAPS_LOCK->HidKeyMapper.CAPS_LOCK;KeyEvent.KEYCODE_SYSRQ->HidKeyMapper.PRINT_SCREEN;KeyEvent.KEYCODE_SCROLL_LOCK->HidKeyMapper.SCROLL_LOCK;KeyEvent.KEYCODE_BREAK->HidKeyMapper.PAUSE;else->return false};return sendKey(usage,if(event.isShiftPressed)HidKeyMapper.MODIFIER_LEFT_SHIFT else 0)}
    override fun onDestroy(){repeatHandler.removeCallbacksAndMessages(null);ConnectionNotification.clear(this);hid?.close();hid=null;super.onDestroy()}

    private inner class RemoteEditText(context:Context,private val emit:(String)->Unit):EditText(context){override fun onCreateInputConnection(outAttrs:EditorInfo):InputConnection{val base=super.onCreateInputConnection(outAttrs);return object:InputConnectionWrapper(base,false){override fun commitText(text:CharSequence?,newCursorPosition:Int):Boolean{text?.toString()?.takeIf{it.isNotEmpty()}?.let{emit(it.replace("\r","\n"))};return super.commitText(text,newCursorPosition)};override fun deleteSurroundingText(beforeLength:Int,afterLength:Int):Boolean{if(beforeLength>0)repeat(beforeLength.coerceAtMost(32)){emit("\b")};if(afterLength>0)repeat(afterLength.coerceAtMost(32)){emit("\u0000${HidKeyMapper.DELETE}")};return super.deleteSurroundingText(beforeLength,afterLength)};override fun deleteSurroundingTextInCodePoints(beforeLength:Int,afterLength:Int):Boolean{if(beforeLength>0)repeat(beforeLength.coerceAtMost(32)){emit("\b")};if(afterLength>0)repeat(afterLength.coerceAtMost(32)){emit("\u0000${HidKeyMapper.DELETE}")};return super.deleteSurroundingTextInCodePoints(beforeLength,afterLength)};override fun sendKeyEvent(event:KeyEvent):Boolean{val handled=sendKeyEventToLaptop(event);val local=super.sendKeyEvent(event);return handled||local};override fun performEditorAction(actionCode:Int):Boolean{if(actionCode==EditorInfo.IME_ACTION_DONE||actionCode==EditorInfo.IME_ACTION_GO||actionCode==EditorInfo.IME_ACTION_NEXT||actionCode==EditorInfo.IME_ACTION_SEND||actionCode==EditorInfo.IME_ACTION_SEARCH)sendKey(HidKeyMapper.ENTER);return super.performEditorAction(actionCode)}}};private fun sendKeyEventToLaptop(event:KeyEvent):Boolean{if(hid?.isConnected()!=true)return false;return this@MainActivity.sendKeyEvent(event)}}
    companion object{private const val REQUEST_BLUETOOTH=77;private const val REQUEST_NOTIFICATIONS=78}
}
