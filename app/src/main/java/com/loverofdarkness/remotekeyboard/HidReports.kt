package com.loverofdarkness.remotekeyboard

/** HID report descriptor and boot-keyboard payloads. */
object HidReports {
    const val REPORT_ID_KEYBOARD = 1

    // IMPORTANT: sendReport() receives the Report ID separately, so the
    // payload must be 8 bytes, NOT 9. The descriptor is:
    // modifier(1) + reserved(1) + 6 key usages(6) = 8 bytes.
    const val KEYBOARD_REPORT_SIZE = 8

    val KEYBOARD_DESCRIPTOR = byteArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,
        0x85.toByte(), REPORT_ID_KEYBOARD.toByte(),
        0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
        0x95.toByte(), 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x03, 0x91.toByte(), 0x01,
        0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
        0xC0.toByte()
    )

    val HID_INFORMATION = byteArrayOf(0x11, 0x01, 0x00, 0x01)
}

object ReportBuilder {
    const val MOD_LEFT_CTRL = 0x01
    const val MOD_LEFT_SHIFT = 0x02
    const val MOD_LEFT_ALT = 0x04
    const val MOD_LEFT_GUI = 0x08

    /** 8-byte boot keyboard input payload; Report ID is sent separately. */
    fun keyboard(modifiers: Int, usage: Int): ByteArray = byteArrayOf(
        (modifiers and 0xFF).toByte(),
        0,
        (usage and 0xFF).toByte(),
        0, 0, 0, 0, 0
    )

    fun keyboardEmpty(): ByteArray = keyboard(0, 0)
}
