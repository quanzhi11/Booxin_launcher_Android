package com.booxin.launcher.ui.launch.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Bluetooth / USB keyboard and gamepad → Minecraft keys / look.
 *
 * Defaults mirror FCL-style mobile controller mapping:
 * A jump, B drop, X inventory, Y swap; L2/R2 attack/use; sticks move/look.
 */
object PhysicalInputController {
    private const val STICK_DIGITAL = 0.45f
    private const val TRIGGER_DIGITAL = 0.55f
    private const val LOOK_TICK_MS = 16L

    @Volatile
    var keyboardEnabled: Boolean = true

    @Volatile
    var gamepadEnabled: Boolean = true

    /** Look stick pixels per frame at full deflection (scaled by prefs). */
    @Volatile
    var lookSensitivity: Float = 10f

    @Volatile
    var stickDeadzone: Float = 0.22f

    @Volatile
    var hideOnScreenWhenGamepad: Boolean = false

    /** Fired once when a gamepad is actively used and hide-on-screen is on. */
    @Volatile
    var onGamepadActive: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lookRunnable = object : Runnable {
        override fun run() {
            if (!gamepadEnabled) return
            val dz = stickDeadzone
            val rx = if (abs(axisRX) > dz) axisRX else 0f
            val ry = if (abs(axisRY) > dz) axisRY else 0f
            if (rx != 0f || ry != 0f) {
                val sens = lookSensitivity
                GameInput.setPointer(
                    GameInput.pointerX + (rx * sens).toInt(),
                    GameInput.pointerY + (ry * sens).toInt()
                )
                mainHandler.postDelayed(this, LOOK_TICK_MS)
            } else {
                looking = false
            }
        }
    }

    @Volatile private var axisLX = 0f
    @Volatile private var axisLY = 0f
    @Volatile private var axisRX = 0f
    @Volatile private var axisRY = 0f
    @Volatile private var looking = false
    @Volatile private var hideControlsNotified = false

    private var wasdW = false
    private var wasdA = false
    private var wasdS = false
    private var wasdD = false
    private var triggerL = false
    private var triggerR = false
    private var hatLeft = false
    private var hatRight = false
    private var hatUp = false
    private var hatDown = false

    fun loadFromPrefs(context: Context) {
        keyboardEnabled = LaunchControlPrefs.isKeyboardEnabled(context)
        gamepadEnabled = LaunchControlPrefs.isGamepadEnabled(context)
        lookSensitivity = LaunchControlPrefs.gamepadLookSensitivity(context)
        stickDeadzone = LaunchControlPrefs.gamepadDeadzone(context)
        hideOnScreenWhenGamepad = LaunchControlPrefs.hideControlsOnGamepad(context)
        hideControlsNotified = false
    }

    fun releaseAll() {
        mainHandler.removeCallbacks(lookRunnable)
        looking = false
        setWasd(w = false, a = false, s = false, d = false)
        if (triggerL) {
            GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, false)
            triggerL = false
        }
        if (triggerR) {
            GameInput.sendKeyEvent(GameInput.MOUSE_RIGHT, false)
            triggerR = false
        }
        applyHat(false, false, false, false)
        axisLX = 0f
        axisLY = 0f
        axisRX = 0f
        axisRY = 0f
        hideControlsNotified = false
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount > 0 && event.action == KeyEvent.ACTION_DOWN) {
            // Allow text repeat for chat chars only.
            if (!keyboardEnabled || isGamepadKey(event)) return false
        }
        if (AndroidKeyMap.isSystemKey(event.keyCode)) return false

        if (isGamepadKey(event)) {
            if (!gamepadEnabled) return false
            markGamepadActive()
            return handleGamepadButton(event)
        }

        if (!keyboardEnabled) return false
        if (!isPhysicalKeyboard(event)) return false

        val press = event.action == KeyEvent.ACTION_DOWN
        val code = AndroidKeyMap.toGameCode(event.keyCode)
        if (code != null) {
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                GameInput.sendKeyEvent(code, press)
            }
        }
        // Printable chars for chat / command line (skip modifiers-only).
        if (press && event.unicodeChar != 0 && !event.isCtrlPressed && !event.isMetaPressed) {
            val ch = event.unicodeChar.toChar()
            if (!ch.isISOControl()) {
                GameInput.sendChar(ch)
            }
        }
        return code != null || (press && event.unicodeChar != 0)
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!gamepadEnabled) return false
        if (!isGamepadMotion(event)) return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE &&
            event.actionMasked != MotionEvent.ACTION_HOVER_MOVE
        ) {
            return false
        }
        markGamepadActive()

        val lx = axisOrZero(event, MotionEvent.AXIS_X)
        val ly = axisOrZero(event, MotionEvent.AXIS_Y)
        val rx = firstAxis(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX)
        val ry = firstAxis(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)
        val lTrigger = firstAxis(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        val rTrigger = firstAxis(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
        val hatX = axisOrZero(event, MotionEvent.AXIS_HAT_X)
        val hatY = axisOrZero(event, MotionEvent.AXIS_HAT_Y)

        axisLX = lx
        axisLY = ly
        axisRX = rx
        axisRY = ry

        val dz = stickDeadzone
        setWasd(
            w = ly < -STICK_DIGITAL,
            a = lx < -STICK_DIGITAL,
            s = ly > STICK_DIGITAL,
            d = lx > STICK_DIGITAL
        )

        setDigital(GameInput.MOUSE_LEFT, lTrigger > TRIGGER_DIGITAL, isLeft = true)
        setDigital(GameInput.MOUSE_RIGHT, rTrigger > TRIGGER_DIGITAL, isLeft = false)

        applyHat(
            left = hatX < -0.85f,
            right = hatX > 0.85f,
            up = hatY < -0.85f,
            down = hatY > 0.85f
        )

        val lookActive = abs(rx) > dz || abs(ry) > dz
        if (lookActive && !looking) {
            looking = true
            mainHandler.post(lookRunnable)
        }
        return true
    }

    fun connectedDeviceSummary(context: Context): String {
        val names = mutableListOf<String>()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual) continue
            val sources = d.sources
            val kb = (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
                d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
            val pad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (kb || pad) {
                val kind = when {
                    kb && pad -> "键盘/手柄"
                    kb -> "键盘"
                    else -> "手柄"
                }
                names += "${d.name}（$kind）"
            }
        }
        return if (names.isEmpty()) {
            context.getString(com.booxin.launcher.R.string.control_physical_none)
        } else {
            names.joinToString("\n")
        }
    }

    private fun handleGamepadButton(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }
        val press = event.action == KeyEvent.ACTION_DOWN
        val mapped = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> GlfwKeys.KEY_SPACE
            KeyEvent.KEYCODE_BUTTON_B -> GlfwKeys.KEY_Q
            KeyEvent.KEYCODE_BUTTON_X -> GlfwKeys.KEY_E
            KeyEvent.KEYCODE_BUTTON_Y -> GlfwKeys.KEY_F
            KeyEvent.KEYCODE_BUTTON_L1 -> GameInput.MOUSE_SCROLL_UP
            KeyEvent.KEYCODE_BUTTON_R1 -> GameInput.MOUSE_SCROLL_DOWN
            KeyEvent.KEYCODE_BUTTON_L2 -> GameInput.MOUSE_LEFT
            KeyEvent.KEYCODE_BUTTON_R2 -> GameInput.MOUSE_RIGHT
            KeyEvent.KEYCODE_BUTTON_THUMBL -> GlfwKeys.KEY_LEFT_CONTROL
            KeyEvent.KEYCODE_BUTTON_THUMBR -> GlfwKeys.KEY_LEFT_SHIFT
            KeyEvent.KEYCODE_BUTTON_START -> GlfwKeys.KEY_ESCAPE
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_MODE -> GlfwKeys.KEY_TAB
            KeyEvent.KEYCODE_DPAD_UP -> GlfwKeys.KEY_LEFT_SHIFT
            KeyEvent.KEYCODE_DPAD_DOWN -> GlfwKeys.KEY_O
            KeyEvent.KEYCODE_DPAD_LEFT -> GlfwKeys.KEY_J
            KeyEvent.KEYCODE_DPAD_RIGHT -> GlfwKeys.KEY_K
            else -> null
        } ?: return false

        when (mapped) {
            GameInput.MOUSE_SCROLL_UP, GameInput.MOUSE_SCROLL_DOWN -> {
                if (press) GameInput.sendKeyEvent(mapped, true)
            }
            else -> GameInput.sendKeyEvent(mapped, press)
        }
        return true
    }

    private fun setWasd(w: Boolean, a: Boolean, s: Boolean, d: Boolean) {
        if (w != wasdW) {
            GameInput.sendKeyEvent(GlfwKeys.KEY_W, w)
            wasdW = w
        }
        if (a != wasdA) {
            GameInput.sendKeyEvent(GlfwKeys.KEY_A, a)
            wasdA = a
        }
        if (s != wasdS) {
            GameInput.sendKeyEvent(GlfwKeys.KEY_S, s)
            wasdS = s
        }
        if (d != wasdD) {
            GameInput.sendKeyEvent(GlfwKeys.KEY_D, d)
            wasdD = d
        }
    }

    private fun applyHat(left: Boolean, right: Boolean, up: Boolean, down: Boolean) {
        if (left != hatLeft) {
            GameInput.sendKeyEvent(GlfwKeys.KEY_J, left)
            hatLeft = left
        }
        if (right != hatRight) {
            GameInput.sendKeyEvent(GlfwKeys.KEY_K, right)
            hatRight = right
        }
        if (up != hatUp) {
            GameInput.sendKeyEvent(GlfwKeys.KEY_LEFT_SHIFT, up)
            hatUp = up
        }
        if (down != hatDown) {
            GameInput.sendKeyEvent(GlfwKeys.KEY_O, down)
            hatDown = down
        }
    }

    private fun setDigital(code: Int, down: Boolean, isLeft: Boolean) {
        val prev = if (isLeft) triggerL else triggerR
        if (down == prev) return
        if (isLeft) triggerL = down else triggerR = down
        GameInput.sendKeyEvent(code, down)
    }

    private fun markGamepadActive() {
        if (hideOnScreenWhenGamepad && !hideControlsNotified) {
            hideControlsNotified = true
            onGamepadActive?.invoke()
        }
    }

    private fun isPhysicalKeyboard(event: KeyEvent): Boolean {
        val device = event.device
        if (device != null) {
            if (device.isVirtual) return false
            if (device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true
            if (event.isFromSource(InputDevice.SOURCE_KEYBOARD) &&
                !event.isFromSource(InputDevice.SOURCE_GAMEPAD) &&
                !event.isFromSource(InputDevice.SOURCE_JOYSTICK)
            ) {
                return true
            }
        }
        // Some BT keyboards omit device; still treat non-gamepad KEYBOARD source as physical.
        return event.isFromSource(InputDevice.SOURCE_KEYBOARD) &&
            !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
            !isGamepadKey(event)
    }

    private fun isGamepadKey(event: KeyEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        ) {
            return true
        }
        val device = event.device ?: return false
        val sources = device.sources
        if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        ) {
            return true
        }
        return event.keyCode in GAMEPAD_BUTTONS
    }

    private fun isGamepadMotion(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        ) {
            return true
        }
        val device = event.device ?: return false
        val sources = device.sources
        return (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    }

    private fun axisOrZero(event: MotionEvent, axis: Int): Float {
        val v = event.getAxisValue(axis)
        return if (v.isNaN()) 0f else v
    }

    private fun firstAxis(event: MotionEvent, vararg axes: Int): Float {
        for (axis in axes) {
            val v = event.getAxisValue(axis)
            if (!v.isNaN() && abs(v) > 0.001f) return v
        }
        return 0f
    }

    private val GAMEPAD_BUTTONS = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_MODE,
        KeyEvent.KEYCODE_BUTTON_1,
        KeyEvent.KEYCODE_BUTTON_2,
        KeyEvent.KEYCODE_BUTTON_3,
        KeyEvent.KEYCODE_BUTTON_4,
        KeyEvent.KEYCODE_BUTTON_5,
        KeyEvent.KEYCODE_BUTTON_6,
        KeyEvent.KEYCODE_BUTTON_7,
        KeyEvent.KEYCODE_BUTTON_8,
        KeyEvent.KEYCODE_BUTTON_9,
        KeyEvent.KEYCODE_BUTTON_10,
        KeyEvent.KEYCODE_BUTTON_11,
        KeyEvent.KEYCODE_BUTTON_12,
        KeyEvent.KEYCODE_BUTTON_13,
        KeyEvent.KEYCODE_BUTTON_14,
        KeyEvent.KEYCODE_BUTTON_15,
        KeyEvent.KEYCODE_BUTTON_16
    )
}
