package com.booxin.launcher.ui.launch.input

/**
 * Full on-screen control layout (buttons + joystick + floating ball).
 */
data class ControlLayoutData(
    val buttons: List<ControlButtonSpec>,
    val joystick: JoystickSpec = JoystickSpec(),
    val floatingBall: FloatingBallSpec = FloatingBallSpec()
) {
    data class JoystickSpec(
        val x: Float = 0.13f,
        val y: Float = 0.84f,
        val sizeDp: Int = 150
    )

    data class FloatingBallSpec(
        val x: Float = 0.96f,
        val y: Float = 0.38f
    )
}
