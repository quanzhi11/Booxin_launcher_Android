package com.booxin.launcher.ui.launch.input

/**
 * One on-screen control button. Position is the center as 0..1 of the parent size.
 */
data class ControlButtonSpec(
    val id: String,
    val label: String,
    val kind: Kind,
    val code: Int,
    val x: Float,
    val y: Float,
    val sizeDp: Int = 52
) {
    enum class Kind {
        KEY_HOLD,
        KEY_TAP,
        MOUSE_HOLD,
        SCROLL
    }
}
