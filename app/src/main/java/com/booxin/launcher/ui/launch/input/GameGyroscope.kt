package com.booxin.launcher.ui.launch.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.booxin.runtime.BooxinBridge

/**
 * Game-look gyroscope. Only applies while the cursor is grabbed (in-world).
 */
class GameGyroscope(
    context: Context,
    sensitivity: Float = 3.5f
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile
    var sensitivity: Float = sensitivity

    private var registered = false
    private var timestampNs = 0L
    private var accumX = 0f
    private var accumY = 0f

    val available: Boolean get() = sensor != null

    fun setEnabled(enabled: Boolean) {
        if (enabled) enable() else disable()
    }

    fun enable() {
        if (registered || sensor == null) return
        timestampNs = 0L
        accumX = 0f
        accumY = 0f
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        registered = true
    }

    fun disable() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
        timestampNs = 0L
        accumX = 0f
        accumY = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!BooxinBridge.isGrabbing()) {
            timestampNs = 0L
            accumX = 0f
            accumY = 0f
            return
        }
        if (timestampNs != 0L) {
            val dt = (event.timestamp - timestampNs) * NS2S
            val sens = sensitivity * LOOK_SCALE
            accumX += event.values[0] * dt * sens
            accumY += event.values[1] * dt * sens
            // Apply when we have at least ~0.5px of motion to avoid jitter spam.
            if (kotlin.math.abs(accumX) >= 0.5f || kotlin.math.abs(accumY) >= 0.5f) {
                val dx = accumX
                val dy = accumY
                accumX = 0f
                accumY = 0f
                GameInput.setPointer(
                    (GameInput.pointerX - dx).toInt(),
                    (GameInput.pointerY + dy).toInt()
                )
            }
        }
        timestampNs = event.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        // Nanoseconds → seconds.
        private const val NS2S = 1.0f / 1_000_000_000.0f
        /** Extra gain so mid-slider feels responsive on phone gyros. */
        private const val LOOK_SCALE = 220f
    }
}
