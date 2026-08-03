package com.booxin.launcher.ui.launch.input

/**
 * One on-screen control button. Position is the center as 0..1 of the parent size.
 *
 * [code] is the primary key (backward compatible). When [codes] has 2+ entries,
 * the button fires a key combination (modifiers first, then main key).
 */
data class ControlButtonSpec(
    val id: String,
    val label: String,
    val kind: Kind,
    val code: Int,
    val x: Float,
    val y: Float,
    val sizeDp: Int = 52,
    val codes: List<Int> = emptyList()
) {
    enum class Kind {
        KEY_HOLD,
        KEY_TAP,
        MOUSE_HOLD,
        SCROLL,
        /** Toggle Android soft keyboard (chat / commands). */
        SOFT_KEYBOARD
    }

    /** Resolved key sequence for KEY_TAP / KEY_HOLD. */
    fun effectiveCodes(): List<Int> =
        if (codes.size >= 2) codes else listOf(code)
}
