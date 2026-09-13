package com.booxin.launcher.ui.controller

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import kotlin.math.abs

/**
 * Classifies keyboard / gamepad keys for launcher shell navigation.
 */
object ControllerInput {

    private const val AXIS_DEADZONE = 0.55f

    enum class Action {
        TAB_PREV,
        TAB_NEXT,
        CONFIRM,
        BACK,
        DPAD_UP,
        DPAD_DOWN,
        DPAD_LEFT,
        DPAD_RIGHT
    }

    fun isTextEditing(focused: View?): Boolean {
        if (focused == null) return false
        if (focused is EditText) return focused.isFocused
        return focused.onCheckIsTextEditor()
    }

    fun actionFor(event: KeyEvent): Action? {
        if (event.action != KeyEvent.ACTION_DOWN) return null
        if (event.repeatCount > 0 && event.keyCode !in DPAD_KEYS) return null
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_PAGE_UP -> Action.TAB_PREV

            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_PAGE_DOWN -> Action.TAB_NEXT

            KeyEvent.KEYCODE_LEFT_BRACKET -> Action.TAB_PREV
            KeyEvent.KEYCODE_RIGHT_BRACKET -> Action.TAB_NEXT
            KeyEvent.KEYCODE_Q -> if (event.isCtrlPressed) Action.TAB_PREV else null
            KeyEvent.KEYCODE_E -> if (event.isCtrlPressed) Action.TAB_NEXT else null

            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> Action.CONFIRM

            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_ESCAPE -> Action.BACK

            KeyEvent.KEYCODE_DPAD_UP -> Action.DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Action.DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Action.DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Action.DPAD_RIGHT

            KeyEvent.KEYCODE_TAB ->
                if (event.isShiftPressed) Action.TAB_PREV else Action.TAB_NEXT

            else -> null
        }
    }

    /**
     * Convert joystick / hat axes into a discrete D-pad action (edge triggered by caller).
     */
    fun axisAction(event: MotionEvent): Action? {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return null
        val source = event.source
        val isStick =
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!isStick) return null

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        axisToAction(hatX, hatY)?.let { return it }

        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        return axisToAction(x, y)
    }

    private fun axisToAction(x: Float, y: Float): Action? {
        if (abs(x) < AXIS_DEADZONE && abs(y) < AXIS_DEADZONE) return null
        return if (abs(x) >= abs(y)) {
            if (x > 0) Action.DPAD_RIGHT else Action.DPAD_LEFT
        } else {
            if (y > 0) Action.DPAD_DOWN else Action.DPAD_UP
        }
    }

    fun isGamepadOrKeyboard(device: InputDevice?): Boolean {
        if (device == null || device.isVirtual) return false
        return deviceKindLabel(device) != DeviceKind.OTHER
    }

    fun isPhysicalMouse(device: InputDevice?): Boolean {
        if (device == null || device.isVirtual) return false
        val sources = device.sources
        return (sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (sources and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
    }

    fun deviceKindLabel(device: InputDevice): DeviceKind {
        val sources = device.sources
        val gamepad =
            (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        val keyboard =
            (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
                device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        val mouse = isPhysicalMouse(device)
        return when {
            gamepad -> DeviceKind.GAMEPAD
            keyboard -> DeviceKind.KEYBOARD
            mouse -> DeviceKind.MOUSE
            else -> DeviceKind.OTHER
        }
    }

    enum class DeviceKind { GAMEPAD, KEYBOARD, MOUSE, COMBO, OTHER }

    private val DPAD_KEYS = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT
    )
}
