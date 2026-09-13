package com.booxin.launcher.ui.launch.input

import android.content.Context

/** Lightweight prefs for in-game control toggles (gyro, physical input, etc.). */
object LaunchControlPrefs {
    private const val PREFS = "booxin_launch_controls"
    private const val KEY_GYRO = "gyro_enabled"
    private const val KEY_GYRO_SENS = "gyro_sensitivity_progress"
    private const val KEY_KEYBOARD = "physical_keyboard_enabled"
    private const val KEY_GAMEPAD = "physical_gamepad_enabled"
    private const val KEY_GAMEPAD_LOOK = "gamepad_look_sens_progress"
    private const val KEY_HIDE_ON_GAMEPAD = "hide_controls_on_gamepad"

    /** SeekBar progress range. */
    const val GYRO_SENS_MIN = 1
    const val GYRO_SENS_MAX = 100
    /** Default sits a bit above mid so first-time feel is usable. */
    const val GYRO_SENS_DEFAULT = 60

    const val GAMEPAD_LOOK_MIN = 1
    const val GAMEPAD_LOOK_MAX = 100
    const val GAMEPAD_LOOK_DEFAULT = 45

    fun isGyroEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GYRO, false)

    fun setGyroEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GYRO, enabled).apply()
    }

    fun gyroSensitivityProgress(context: Context): Int =
        prefs(context).getInt(KEY_GYRO_SENS, GYRO_SENS_DEFAULT)
            .coerceIn(GYRO_SENS_MIN, GYRO_SENS_MAX)

    fun setGyroSensitivityProgress(context: Context, progress: Int) {
        prefs(context).edit()
            .putInt(KEY_GYRO_SENS, progress.coerceIn(GYRO_SENS_MIN, GYRO_SENS_MAX))
            .apply()
    }

    /** Maps seek progress to runtime multiplier (~0.6 … 14). */
    fun gyroSensitivity(context: Context): Float =
        progressToSensitivity(gyroSensitivityProgress(context))

    fun progressToSensitivity(progress: Int): Float {
        val p = progress.coerceIn(GYRO_SENS_MIN, GYRO_SENS_MAX)
        return 0.6f + (p / 100f) * 13.4f
    }

    /** Bluetooth / USB keyboard — on by default once paired in system settings. */
    fun isKeyboardEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEYBOARD, true)

    fun setKeyboardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEYBOARD, enabled).apply()
    }

    /** Bluetooth / USB gamepad — on by default. */
    fun isGamepadEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GAMEPAD, true)

    fun setGamepadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GAMEPAD, enabled).apply()
    }

    fun gamepadLookProgress(context: Context): Int =
        prefs(context).getInt(KEY_GAMEPAD_LOOK, GAMEPAD_LOOK_DEFAULT)
            .coerceIn(GAMEPAD_LOOK_MIN, GAMEPAD_LOOK_MAX)

    fun setGamepadLookProgress(context: Context, progress: Int) {
        prefs(context).edit()
            .putInt(KEY_GAMEPAD_LOOK, progress.coerceIn(GAMEPAD_LOOK_MIN, GAMEPAD_LOOK_MAX))
            .apply()
    }

    /** Right-stick look speed (~3 … 28 px/tick). */
    fun gamepadLookSensitivity(context: Context): Float =
        progressToGamepadLook(gamepadLookProgress(context))

    fun progressToGamepadLook(progress: Int): Float {
        val p = progress.coerceIn(GAMEPAD_LOOK_MIN, GAMEPAD_LOOK_MAX)
        return 3f + (p / 100f) * 25f
    }

    fun gamepadDeadzone(context: Context): Float = 0.22f

    fun hideControlsOnGamepad(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_ON_GAMEPAD, false)

    fun setHideControlsOnGamepad(context: Context, hide: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_ON_GAMEPAD, hide).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
