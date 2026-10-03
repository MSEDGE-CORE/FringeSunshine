package com.wxz.sunshineserverandroid.input

import android.view.KeyEvent

/**
 * Moonlight 客户端发来的 keyCode 是 Windows 虚拟键码（VK_*，
 * 见 moonlight-common-c Limelight.h 与 moonlight-android KeyboardTranslator），
 * 需要先翻成 Android KeyCode 才能构造 KeyEvent 注入。
 */
internal object KeyMap {

    /** Windows VK → Android KeyCode，null 表示该键本机无对应 */
    fun vkToAndroid(vk: Int): Int? = when (vk and 0xFFFF) {
        0x08 -> KeyEvent.KEYCODE_DEL
        0x09 -> KeyEvent.KEYCODE_TAB
        0x0C -> KeyEvent.KEYCODE_CLEAR
        0x0D -> KeyEvent.KEYCODE_ENTER
        0x10 -> KeyEvent.KEYCODE_SHIFT_LEFT
        0x11 -> KeyEvent.KEYCODE_CTRL_LEFT
        0x12 -> KeyEvent.KEYCODE_ALT_LEFT
        0x13 -> KeyEvent.KEYCODE_BREAK
        0x14 -> KeyEvent.KEYCODE_CAPS_LOCK
        0x1B -> KeyEvent.KEYCODE_ESCAPE
        0x1C -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        0x20 -> KeyEvent.KEYCODE_SPACE
        0x21 -> KeyEvent.KEYCODE_PAGE_UP
        0x22 -> KeyEvent.KEYCODE_PAGE_DOWN
        0x23 -> KeyEvent.KEYCODE_MOVE_END
        0x24 -> KeyEvent.KEYCODE_MOVE_HOME
        0x25 -> KeyEvent.KEYCODE_DPAD_LEFT
        0x26 -> KeyEvent.KEYCODE_DPAD_UP
        0x27 -> KeyEvent.KEYCODE_DPAD_RIGHT
        0x28 -> KeyEvent.KEYCODE_DPAD_DOWN
        0x2C -> KeyEvent.KEYCODE_SYSRQ
        0x2D -> KeyEvent.KEYCODE_INSERT
        0x2E -> KeyEvent.KEYCODE_FORWARD_DEL
        0x5B -> KeyEvent.KEYCODE_META_LEFT
        0x5C -> KeyEvent.KEYCODE_META_RIGHT
        0x5D -> KeyEvent.KEYCODE_MENU
        0x6A -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY
        0x6B -> KeyEvent.KEYCODE_NUMPAD_ADD
        0x6D -> KeyEvent.KEYCODE_NUMPAD_SUBTRACT
        0x6E -> KeyEvent.KEYCODE_NUMPAD_DOT
        0x6F -> KeyEvent.KEYCODE_NUMPAD_DIVIDE
        0x90 -> KeyEvent.KEYCODE_NUM_LOCK
        0x91 -> KeyEvent.KEYCODE_SCROLL_LOCK
        0xA0 -> KeyEvent.KEYCODE_SHIFT_LEFT
        0xA1 -> KeyEvent.KEYCODE_SHIFT_RIGHT
        0xA2 -> KeyEvent.KEYCODE_CTRL_LEFT
        0xA3 -> KeyEvent.KEYCODE_CTRL_RIGHT
        0xA4 -> KeyEvent.KEYCODE_ALT_LEFT
        0xA5 -> KeyEvent.KEYCODE_ALT_RIGHT
        0xBA -> KeyEvent.KEYCODE_SEMICOLON
        0xBB -> KeyEvent.KEYCODE_EQUALS
        0xBC -> KeyEvent.KEYCODE_COMMA
        0xBD -> KeyEvent.KEYCODE_MINUS
        0xBE -> KeyEvent.KEYCODE_PERIOD
        0xBF -> KeyEvent.KEYCODE_SLASH
        0xC0 -> KeyEvent.KEYCODE_GRAVE
        0xDB -> KeyEvent.KEYCODE_LEFT_BRACKET
        0xDC -> KeyEvent.KEYCODE_BACKSLASH
        0xDD -> KeyEvent.KEYCODE_RIGHT_BRACKET
        0xDE -> KeyEvent.KEYCODE_APOSTROPHE
        else -> when {
            vk in 0x30..0x39 -> KeyEvent.KEYCODE_0 + (vk - 0x30)
            vk in 0x41..0x5A -> KeyEvent.KEYCODE_A + (vk - 0x41)
            vk in 0x60..0x69 -> KeyEvent.KEYCODE_NUMPAD_0 + (vk - 0x60)
            vk in 0x70..0x7B -> KeyEvent.KEYCODE_F1 + (vk - 0x70)
            else -> null
        }
    }

    /** moonlight MODIFIER_* 位域 → Android KeyEvent metaState */
    fun metaState(modifiers: Int): Int {
        var meta = 0
        if (modifiers and 0x01 != 0) meta = meta or (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        if (modifiers and 0x02 != 0) meta = meta or (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        if (modifiers and 0x04 != 0) meta = meta or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        if (modifiers and 0x08 != 0) meta = meta or (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)
        return meta
    }
}
