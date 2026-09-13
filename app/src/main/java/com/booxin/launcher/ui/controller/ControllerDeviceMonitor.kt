package com.booxin.launcher.ui.controller

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Watches Bluetooth / USB keyboards and gamepads appearing or leaving.
 */
class ControllerDeviceMonitor(
    context: Context,
    private val onChanged: (connected: List<InputDevice>, added: InputDevice?, removedName: String?) -> Unit
) : InputManager.InputDeviceListener {

    private val appContext = context.applicationContext
    private val inputManager =
        appContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val knownIds = CopyOnWriteArraySet<Int>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        knownIds.clear()
        knownIds.addAll(currentDevices().map { it.id })
        inputManager.registerInputDeviceListener(this, mainHandler)
        onChanged(currentDevices(), null, null)
    }

    fun stop() {
        if (!started) return
        started = false
        inputManager.unregisterInputDeviceListener(this)
    }

    fun currentDevices(): List<InputDevice> {
        val out = ArrayList<InputDevice>()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (ControllerInput.isGamepadOrKeyboard(device)) {
                out.add(device)
            }
        }
        return out
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (!ControllerInput.isGamepadOrKeyboard(device)) return
        if (!knownIds.add(deviceId)) return
        onChanged(currentDevices(), device, null)
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (!knownIds.remove(deviceId)) return
        onChanged(currentDevices(), null, "device#$deviceId")
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId)
        if (device != null && ControllerInput.isGamepadOrKeyboard(device)) {
            knownIds.add(deviceId)
        } else {
            knownIds.remove(deviceId)
        }
        onChanged(currentDevices(), null, null)
    }
}
