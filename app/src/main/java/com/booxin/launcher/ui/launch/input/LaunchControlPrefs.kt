package com.booxin.launcher.ui.launch.input

import android.content.Context

/** Lightweight prefs for in-game control toggles (gyro, etc.). */
object LaunchControlPrefs {
    private const val PREFS = "booxin_launch_controls"
    private const val KEY_GYRO = "gyro_enabled"
    private const val KEY_GYRO_SENS = "gyro_sensitivity_progress"

    /** SeekBar progress range. */
    const val GYRO_SENS_MIN = 1
    const val GYRO_SENS_MAX = 100
    /** Default sits a bit above mid so first-time feel is usable. */
    const val GYRO_SENS_DEFAULT = 60

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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
