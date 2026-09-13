package com.booxin.launcher.ui.launch.input

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.booxin.runtime.BooxinBridge
import org.libsdl.app.SDLActivity

/**
 * 26.3+ uses SDL, not GLFW callbacks. Touch/buttons still go through
 * [BooxinBridge]/LWJGL stack queue, but mouseCb/keyCb stay null — events never
 * reach Minecraft unless we also push them into SDL.
 *
 * SDL Android_OnMouse tracks button bitmasks:
 * - ACTION_DOWN: state = buttons after press (`changes = state & ~last`)
 * - ACTION_UP: state = buttons after release (`changes = last & ~state`)
 * Passing BUTTON_PRIMARY on UP makes changes=0 → click never releases / never registers.
 */
object BooxinSdlInput {
    private const val TAG = "BooxinSdlInput"

    @Volatile
    var enabled: Boolean = false
        private set

    /** Mirrors SDL's last_state for Android mouse button masks. */
    @Volatile
    private var buttonState: Int = 0

    fun setEnabled(on: Boolean) {
        enabled = on
        buttonState = 0
        if (on) Log.i(TAG, "SDL input mirror enabled")
    }

    fun mirrorCursor(viewX: Float, viewY: Float) {
        if (!enabled) return
        runCatching {
            SDLActivity.onNativeMouse(
                buttonState,
                MotionEvent.ACTION_MOVE,
                viewX,
                viewY,
                BooxinBridge.isGrabbing()
            )
        }.onFailure { Log.w(TAG, "onNativeMouse move: ${it.message}") }
    }

    fun mirrorMouseButton(glfwButton: Int, pressed: Boolean, viewX: Float, viewY: Float) {
        if (!enabled) return
        val mask = when (glfwButton) {
            GlfwKeys.MOUSE_LEFT -> MotionEvent.BUTTON_PRIMARY
            GlfwKeys.MOUSE_RIGHT -> MotionEvent.BUTTON_SECONDARY
            GlfwKeys.MOUSE_MIDDLE -> MotionEvent.BUTTON_TERTIARY
            else -> MotionEvent.BUTTON_PRIMARY
        }
        val relative = BooxinBridge.isGrabbing()
        if (pressed) {
            buttonState = buttonState or mask
            runCatching {
                SDLActivity.onNativeMouse(
                    buttonState,
                    MotionEvent.ACTION_DOWN,
                    viewX,
                    viewY,
                    relative
                )
            }.onFailure { Log.w(TAG, "onNativeMouse down: ${it.message}") }
        } else {
            // State AFTER release — SDL computes released buttons as last & ~state.
            buttonState = buttonState and mask.inv()
            runCatching {
                SDLActivity.onNativeMouse(
                    buttonState,
                    MotionEvent.ACTION_UP,
                    viewX,
                    viewY,
                    relative
                )
            }.onFailure { Log.w(TAG, "onNativeMouse up: ${it.message}") }
        }
    }

    fun mirrorScroll(yoffset: Double) {
        if (!enabled || yoffset == 0.0) return
        val x = GameInput.pointerX.toFloat()
        val y = GameInput.pointerY.toFloat()
        runCatching {
            SDLActivity.onNativeMouse(
                0,
                MotionEvent.ACTION_SCROLL,
                0f,
                yoffset.toFloat(),
                false
            )
        }.onFailure { Log.w(TAG, "onNativeMouse scroll: ${it.message}") }
        // Keep cursor position coherent after wheel.
        mirrorCursor(x, y)
    }

    fun mirrorKey(glfwKey: Int, pressed: Boolean) {
        if (!enabled) return
        val androidKey = glfwToAndroid(glfwKey) ?: return
        runCatching {
            if (pressed) SDLActivity.onNativeKeyDown(androidKey)
            else SDLActivity.onNativeKeyUp(androidKey)
        }.onFailure { Log.w(TAG, "onNativeKey: ${it.message}") }
    }

    private fun glfwToAndroid(glfw: Int): Int? = when (glfw) {
        GlfwKeys.KEY_SPACE -> KeyEvent.KEYCODE_SPACE
        GlfwKeys.KEY_APOSTROPHE -> KeyEvent.KEYCODE_APOSTROPHE
        GlfwKeys.KEY_COMMA -> KeyEvent.KEYCODE_COMMA
        GlfwKeys.KEY_MINUS -> KeyEvent.KEYCODE_MINUS
        GlfwKeys.KEY_PERIOD -> KeyEvent.KEYCODE_PERIOD
        GlfwKeys.KEY_SLASH -> KeyEvent.KEYCODE_SLASH
        in GlfwKeys.KEY_0..GlfwKeys.KEY_9 ->
            KeyEvent.KEYCODE_0 + (glfw - GlfwKeys.KEY_0)
        GlfwKeys.KEY_SEMICOLON -> KeyEvent.KEYCODE_SEMICOLON
        GlfwKeys.KEY_EQUAL -> KeyEvent.KEYCODE_EQUALS
        in GlfwKeys.KEY_A..GlfwKeys.KEY_Z ->
            KeyEvent.KEYCODE_A + (glfw - GlfwKeys.KEY_A)
        GlfwKeys.KEY_LEFT_BRACKET -> KeyEvent.KEYCODE_LEFT_BRACKET
        GlfwKeys.KEY_BACKSLASH -> KeyEvent.KEYCODE_BACKSLASH
        GlfwKeys.KEY_RIGHT_BRACKET -> KeyEvent.KEYCODE_RIGHT_BRACKET
        GlfwKeys.KEY_GRAVE_ACCENT -> KeyEvent.KEYCODE_GRAVE
        GlfwKeys.KEY_ESCAPE -> KeyEvent.KEYCODE_ESCAPE
        GlfwKeys.KEY_ENTER -> KeyEvent.KEYCODE_ENTER
        GlfwKeys.KEY_TAB -> KeyEvent.KEYCODE_TAB
        GlfwKeys.KEY_BACKSPACE -> KeyEvent.KEYCODE_DEL
        GlfwKeys.KEY_INSERT -> KeyEvent.KEYCODE_INSERT
        GlfwKeys.KEY_DELETE -> KeyEvent.KEYCODE_FORWARD_DEL
        GlfwKeys.KEY_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        GlfwKeys.KEY_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        GlfwKeys.KEY_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        GlfwKeys.KEY_UP -> KeyEvent.KEYCODE_DPAD_UP
        GlfwKeys.KEY_PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
        GlfwKeys.KEY_PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
        GlfwKeys.KEY_HOME -> KeyEvent.KEYCODE_MOVE_HOME
        GlfwKeys.KEY_END -> KeyEvent.KEYCODE_MOVE_END
        GlfwKeys.KEY_CAPS_LOCK -> KeyEvent.KEYCODE_CAPS_LOCK
        in GlfwKeys.KEY_F1..GlfwKeys.KEY_F12 ->
            KeyEvent.KEYCODE_F1 + (glfw - GlfwKeys.KEY_F1)
        GlfwKeys.KEY_LEFT_SHIFT, GlfwKeys.KEY_RIGHT_SHIFT -> KeyEvent.KEYCODE_SHIFT_LEFT
        GlfwKeys.KEY_LEFT_CONTROL, GlfwKeys.KEY_RIGHT_CONTROL -> KeyEvent.KEYCODE_CTRL_LEFT
        GlfwKeys.KEY_LEFT_ALT, GlfwKeys.KEY_RIGHT_ALT -> KeyEvent.KEYCODE_ALT_LEFT
        GlfwKeys.KEY_MENU -> KeyEvent.KEYCODE_MENU
        else -> null
    }
}
