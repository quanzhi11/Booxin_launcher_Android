package com.booxin.launcher.ui.launch.input

import android.view.KeyEvent

/** Android [KeyEvent] → GLFW / virtual mouse codes used by [GameInput]. */
object AndroidKeyMap {

    fun toGameCode(androidKeyCode: Int): Int? = when (androidKeyCode) {
        KeyEvent.KEYCODE_SPACE -> GlfwKeys.KEY_SPACE
        KeyEvent.KEYCODE_APOSTROPHE -> GlfwKeys.KEY_APOSTROPHE
        KeyEvent.KEYCODE_COMMA -> GlfwKeys.KEY_COMMA
        KeyEvent.KEYCODE_MINUS -> GlfwKeys.KEY_MINUS
        KeyEvent.KEYCODE_PERIOD -> GlfwKeys.KEY_PERIOD
        KeyEvent.KEYCODE_SLASH -> GlfwKeys.KEY_SLASH
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
            GlfwKeys.KEY_0 + (androidKeyCode - KeyEvent.KEYCODE_0)
        KeyEvent.KEYCODE_SEMICOLON -> GlfwKeys.KEY_SEMICOLON
        KeyEvent.KEYCODE_EQUALS -> GlfwKeys.KEY_EQUAL
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            GlfwKeys.KEY_A + (androidKeyCode - KeyEvent.KEYCODE_A)
        KeyEvent.KEYCODE_LEFT_BRACKET -> GlfwKeys.KEY_LEFT_BRACKET
        KeyEvent.KEYCODE_BACKSLASH -> GlfwKeys.KEY_BACKSLASH
        KeyEvent.KEYCODE_RIGHT_BRACKET -> GlfwKeys.KEY_RIGHT_BRACKET
        KeyEvent.KEYCODE_GRAVE -> GlfwKeys.KEY_GRAVE_ACCENT
        KeyEvent.KEYCODE_ESCAPE -> GlfwKeys.KEY_ESCAPE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> GlfwKeys.KEY_ENTER
        KeyEvent.KEYCODE_TAB -> GlfwKeys.KEY_TAB
        KeyEvent.KEYCODE_DEL -> GlfwKeys.KEY_BACKSPACE
        KeyEvent.KEYCODE_INSERT -> GlfwKeys.KEY_INSERT
        KeyEvent.KEYCODE_FORWARD_DEL -> GlfwKeys.KEY_DELETE
        KeyEvent.KEYCODE_DPAD_RIGHT -> GlfwKeys.KEY_RIGHT
        KeyEvent.KEYCODE_DPAD_LEFT -> GlfwKeys.KEY_LEFT
        KeyEvent.KEYCODE_DPAD_DOWN -> GlfwKeys.KEY_DOWN
        KeyEvent.KEYCODE_DPAD_UP -> GlfwKeys.KEY_UP
        KeyEvent.KEYCODE_PAGE_UP -> GlfwKeys.KEY_PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> GlfwKeys.KEY_PAGE_DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> GlfwKeys.KEY_HOME
        KeyEvent.KEYCODE_MOVE_END -> GlfwKeys.KEY_END
        KeyEvent.KEYCODE_CAPS_LOCK -> GlfwKeys.KEY_CAPS_LOCK
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            GlfwKeys.KEY_F1 + (androidKeyCode - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_SHIFT_LEFT -> GlfwKeys.KEY_LEFT_SHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> GlfwKeys.KEY_RIGHT_SHIFT
        KeyEvent.KEYCODE_CTRL_LEFT -> GlfwKeys.KEY_LEFT_CONTROL
        KeyEvent.KEYCODE_CTRL_RIGHT -> GlfwKeys.KEY_RIGHT_CONTROL
        KeyEvent.KEYCODE_ALT_LEFT -> GlfwKeys.KEY_LEFT_ALT
        KeyEvent.KEYCODE_ALT_RIGHT -> GlfwKeys.KEY_RIGHT_ALT
        KeyEvent.KEYCODE_META_LEFT -> GlfwKeys.KEY_LEFT_SUPER
        KeyEvent.KEYCODE_META_RIGHT -> GlfwKeys.KEY_RIGHT_SUPER
        KeyEvent.KEYCODE_MENU -> GlfwKeys.KEY_MENU
        KeyEvent.KEYCODE_NUMPAD_0 -> GlfwKeys.KEY_KP_0
        KeyEvent.KEYCODE_NUMPAD_1 -> GlfwKeys.KEY_KP_1
        KeyEvent.KEYCODE_NUMPAD_2 -> GlfwKeys.KEY_KP_2
        KeyEvent.KEYCODE_NUMPAD_3 -> GlfwKeys.KEY_KP_3
        KeyEvent.KEYCODE_NUMPAD_4 -> GlfwKeys.KEY_KP_4
        KeyEvent.KEYCODE_NUMPAD_5 -> GlfwKeys.KEY_KP_5
        KeyEvent.KEYCODE_NUMPAD_6 -> GlfwKeys.KEY_KP_6
        KeyEvent.KEYCODE_NUMPAD_7 -> GlfwKeys.KEY_KP_7
        KeyEvent.KEYCODE_NUMPAD_8 -> GlfwKeys.KEY_KP_8
        KeyEvent.KEYCODE_NUMPAD_9 -> GlfwKeys.KEY_KP_9
        KeyEvent.KEYCODE_NUMPAD_DOT -> GlfwKeys.KEY_KP_DECIMAL
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> GlfwKeys.KEY_KP_DIVIDE
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> GlfwKeys.KEY_KP_MULTIPLY
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> GlfwKeys.KEY_KP_SUBTRACT
        KeyEvent.KEYCODE_NUMPAD_ADD -> GlfwKeys.KEY_KP_ADD
        else -> null
    }

    /** True for volume / power / system nav that must not reach Minecraft. */
    fun isSystemKey(androidKeyCode: Int): Boolean = when (androidKeyCode) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_NOTIFICATION,
        KeyEvent.KEYCODE_BRIGHTNESS_UP,
        KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
        KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_SOFT_SLEEP,
        KeyEvent.KEYCODE_MUTE -> true
        else -> false
    }
}
