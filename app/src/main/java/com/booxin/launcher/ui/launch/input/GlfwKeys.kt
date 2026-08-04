package com.booxin.launcher.ui.launch.input

/**
 * GLFW key / mouse constants used by on-screen controls.
 * Values match glfw3.h.
 */
object GlfwKeys {
    const val KEY_SPACE = 32
    const val KEY_APOSTROPHE = 39
    const val KEY_COMMA = 44
    const val KEY_MINUS = 45
    const val KEY_PERIOD = 46
    const val KEY_SLASH = 47
    const val KEY_0 = 48
    const val KEY_1 = 49
    const val KEY_2 = 50
    const val KEY_3 = 51
    const val KEY_4 = 52
    const val KEY_5 = 53
    const val KEY_6 = 54
    const val KEY_7 = 55
    const val KEY_8 = 56
    const val KEY_9 = 57
    const val KEY_SEMICOLON = 59
    const val KEY_EQUAL = 61
    const val KEY_A = 65
    const val KEY_B = 66
    const val KEY_C = 67
    const val KEY_D = 68
    const val KEY_E = 69
    const val KEY_F = 70
    const val KEY_G = 71
    const val KEY_H = 72
    const val KEY_I = 73
    const val KEY_J = 74
    const val KEY_K = 75
    const val KEY_L = 76
    const val KEY_M = 77
    const val KEY_N = 78
    const val KEY_O = 79
    const val KEY_P = 80
    const val KEY_Q = 81
    const val KEY_R = 82
    const val KEY_S = 83
    const val KEY_T = 84
    const val KEY_U = 85
    const val KEY_V = 86
    const val KEY_W = 87
    const val KEY_X = 88
    const val KEY_Y = 89
    const val KEY_Z = 90
    const val KEY_LEFT_BRACKET = 91
    const val KEY_BACKSLASH = 92
    const val KEY_RIGHT_BRACKET = 93
    const val KEY_GRAVE_ACCENT = 96
    const val KEY_ESCAPE = 256
    const val KEY_ENTER = 257
    const val KEY_TAB = 258
    const val KEY_BACKSPACE = 259
    const val KEY_INSERT = 260
    const val KEY_DELETE = 261
    const val KEY_RIGHT = 262
    const val KEY_LEFT = 263
    const val KEY_DOWN = 264
    const val KEY_UP = 265
    const val KEY_PAGE_UP = 266
    const val KEY_PAGE_DOWN = 267
    const val KEY_HOME = 268
    const val KEY_END = 269
    const val KEY_CAPS_LOCK = 280
    const val KEY_SCROLL_LOCK = 281
    const val KEY_NUM_LOCK = 282
    const val KEY_PRINT_SCREEN = 283
    const val KEY_PAUSE = 284
    const val KEY_F1 = 290
    const val KEY_F2 = 291
    const val KEY_F3 = 292
    const val KEY_F4 = 293
    const val KEY_F5 = 294
    const val KEY_F6 = 295
    const val KEY_F7 = 296
    const val KEY_F8 = 297
    const val KEY_F9 = 298
    const val KEY_F10 = 299
    const val KEY_F11 = 300
    const val KEY_F12 = 301
    const val KEY_KP_0 = 320
    const val KEY_KP_1 = 321
    const val KEY_KP_2 = 322
    const val KEY_KP_3 = 323
    const val KEY_KP_4 = 324
    const val KEY_KP_5 = 325
    const val KEY_KP_6 = 326
    const val KEY_KP_7 = 327
    const val KEY_KP_8 = 328
    const val KEY_KP_9 = 329
    const val KEY_KP_DECIMAL = 330
    const val KEY_KP_DIVIDE = 331
    const val KEY_KP_MULTIPLY = 332
    const val KEY_KP_SUBTRACT = 333
    const val KEY_KP_ADD = 334
    const val KEY_KP_ENTER = 335
    const val KEY_LEFT_SHIFT = 340
    const val KEY_LEFT_CONTROL = 341
    const val KEY_LEFT_ALT = 342
    const val KEY_LEFT_SUPER = 343
    const val KEY_RIGHT_SHIFT = 344
    const val KEY_RIGHT_CONTROL = 345
    const val KEY_RIGHT_ALT = 346
    const val KEY_RIGHT_SUPER = 347
    const val KEY_MENU = 348

    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
    const val MOUSE_MIDDLE = 2

    fun shortLabel(code: Int): String = when (code) {
        KEY_SPACE -> "空格"
        KEY_ESCAPE -> "Esc"
        KEY_ENTER -> "Enter"
        KEY_TAB -> "Tab"
        KEY_BACKSPACE -> "Bksp"
        KEY_INSERT -> "Ins"
        KEY_DELETE -> "Del"
        KEY_RIGHT -> "→"
        KEY_LEFT -> "←"
        KEY_DOWN -> "↓"
        KEY_UP -> "↑"
        KEY_PAGE_UP -> "PgUp"
        KEY_PAGE_DOWN -> "PgDn"
        KEY_HOME -> "Home"
        KEY_END -> "End"
        KEY_CAPS_LOCK -> "Caps"
        KEY_LEFT_SHIFT, KEY_RIGHT_SHIFT -> "Shift"
        KEY_LEFT_CONTROL, KEY_RIGHT_CONTROL -> "Ctrl"
        KEY_LEFT_ALT, KEY_RIGHT_ALT -> "Alt"
        KEY_LEFT_SUPER, KEY_RIGHT_SUPER -> "Win"
        KEY_MENU -> "Menu"
        KEY_APOSTROPHE -> "'"
        KEY_COMMA -> ","
        KEY_MINUS -> "-"
        KEY_PERIOD -> "."
        KEY_SLASH -> "/"
        KEY_SEMICOLON -> ";"
        KEY_EQUAL -> "="
        KEY_LEFT_BRACKET -> "["
        KEY_BACKSLASH -> "\\"
        KEY_RIGHT_BRACKET -> "]"
        KEY_GRAVE_ACCENT -> "`"
        KEY_KP_DIVIDE -> "Num/"
        KEY_KP_MULTIPLY -> "Num*"
        KEY_KP_SUBTRACT -> "Num-"
        KEY_KP_ADD -> "Num+"
        KEY_KP_DECIMAL -> "Num."
        KEY_KP_ENTER -> "NumEnt"
        in KEY_F1..KEY_F12 -> "F${code - KEY_F1 + 1}"
        in KEY_KP_0..KEY_KP_9 -> "Num${code - KEY_KP_0}"
        in KEY_0..KEY_9 -> (code - KEY_0).toString()
        in KEY_A..KEY_Z -> (code - KEY_A + 'A'.code).toChar().toString()
        else -> code.toString()
    }

    fun comboLabel(codes: List<Int>): String =
        codes.joinToString("+") { shortLabel(it) }
}
