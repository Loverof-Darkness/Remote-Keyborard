package com.loverofdarkness.remotekeyboard

/** Minimal USB HID keyboard usage/modifier mapping. */
object HidKeyMapper {
    data class Mapping(val usage: Int, val modifier: Int = 0)

    const val MODIFIER_LEFT_CTRL = 0x01
    const val MODIFIER_LEFT_SHIFT = 0x02
    const val MODIFIER_LEFT_ALT = 0x04
    const val MODIFIER_LEFT_META = 0x08

    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val ENTER = 0x28
    const val ESC = 0x29
    const val INSERT = 0x49
    const val DELETE = 0x4C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val HOME = 0x4A
    const val END = 0x4D
    const val PAGE_UP = 0x4B
    const val PAGE_DOWN = 0x4E
    const val CAPS_LOCK = 0x39
    const val PRINT_SCREEN = 0x46
    const val SCROLL_LOCK = 0x47
    const val PAUSE = 0x48

    private val letters = mapOf(
        'a' to 0x04, 'b' to 0x05, 'c' to 0x06, 'd' to 0x07, 'e' to 0x08,
        'f' to 0x09, 'g' to 0x0A, 'h' to 0x0B, 'i' to 0x0C, 'j' to 0x0D,
        'k' to 0x0E, 'l' to 0x0F, 'm' to 0x10, 'n' to 0x11, 'o' to 0x12,
        'p' to 0x13, 'q' to 0x14, 'r' to 0x15, 's' to 0x16, 't' to 0x17,
        'u' to 0x18, 'v' to 0x19, 'w' to 0x1A, 'x' to 0x1B, 'y' to 0x1C,
        'z' to 0x1D
    )
    private val digits = mapOf('1' to 0x1E, '2' to 0x1F, '3' to 0x20, '4' to 0x21, '5' to 0x22, '6' to 0x23, '7' to 0x24, '8' to 0x25, '9' to 0x26, '0' to 0x27)
    private val plain = mapOf(' ' to 0x2C, '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31, ';' to 0x33, '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37, '/' to 0x38)
    private val shifted = mapOf('!' to '1', '@' to '2', '#' to '3', '$' to '4', '%' to '5', '^' to '6', '&' to '7', '*' to '8', '(' to '9', ')' to '0', '_' to '-', '+' to '=', '{' to '[', '}' to ']', '|' to '\\', ':' to ';', '"' to '\'', '~' to '`', '<' to ',', '>' to '.', '?' to '/')

    fun map(c: Char): Mapping? {
        val lower = c.lowercaseChar()
        letters[lower]?.let { return Mapping(it, if (c.isUpperCase()) MODIFIER_LEFT_SHIFT else 0) }
        digits[c]?.let { return Mapping(it) }
        plain[c]?.let { return Mapping(it) }
        shifted[c]?.let { base -> return Mapping(map(base)?.usage ?: return null, MODIFIER_LEFT_SHIFT) }
        return null
    }

    fun usageFor(name: String): Int? = when (name.trim().uppercase()) {
        "ENTER", "RETURN" -> ENTER
        "BACKSPACE", "BKSP" -> BACKSPACE
        "TAB" -> TAB
        "ESC", "ESCAPE" -> ESC
        "DELETE", "DEL" -> DELETE
        "INSERT", "INS" -> INSERT
        "UP" -> UP
        "DOWN" -> DOWN
        "LEFT" -> LEFT
        "RIGHT" -> RIGHT
        "HOME" -> HOME
        "END" -> END
        "PGUP", "PAGEUP", "PAGE UP" -> PAGE_UP
        "PGDN", "PAGEDOWN", "PAGE DOWN" -> PAGE_DOWN
        "CAPS", "CAPSLOCK", "CAPS LOCK" -> CAPS_LOCK
        "PRINT", "PRINTSCREEN", "PRINT SCREEN" -> PRINT_SCREEN
        "SCROLL", "SCROLLLOCK", "SCROLL LOCK" -> SCROLL_LOCK
        "PAUSE" -> PAUSE
        else -> {
            val n = name.trim().uppercase().removePrefix("F").toIntOrNull()
            if (name.trim().uppercase().startsWith("F") && n in 1..12) 0x39 + n else if (name.trim().length == 1) map(name.trim()[0])?.usage else null
        }
    }

    fun modifierFor(name: String): Int? = when (name.trim().uppercase()) {
        "CTRL", "CONTROL" -> MODIFIER_LEFT_CTRL
        "SHIFT" -> MODIFIER_LEFT_SHIFT
        "ALT" -> MODIFIER_LEFT_ALT
        "META", "WIN", "SUPER", "CMD", "COMMAND" -> MODIFIER_LEFT_META
        else -> null
    }
}
