package com.booxin.launcher.ui.launch.input

/**
 * Full on-screen control layout (buttons + joystick + floating ball + gesture quick).
 */
data class ControlLayoutData(
    val buttons: List<ControlButtonSpec>,
    val joystick: JoystickSpec = JoystickSpec(),
    val floatingBall: FloatingBallSpec = FloatingBallSpec(),
    val gestureQuick: GestureQuickSpec = GestureQuickSpec(),
    /** Default CSS-like chrome for all buttons (per-button style overrides). */
    val buttonStyle: ControlButtonStyle? = null
) {
    data class JoystickSpec(
        val x: Float = 0.13f,
        val y: Float = 0.84f,
        val sizeDp: Int = 150,
        /**
         * On press, pad recenters under the finger; base chases past the rim;
         * springs home on release.
         */
        val follow: Boolean = false
    )

    data class FloatingBallSpec(
        val x: Float = 0.96f,
        val y: Float = 0.38f
    )

    data class GestureQuickSpec(
        val x: Float = 0.96f,
        val y: Float = 0.26f
    )
}
