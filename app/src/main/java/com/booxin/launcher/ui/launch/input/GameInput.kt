package com.booxin.launcher.ui.launch.input

import android.content.res.Resources
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import org.lwjgl.glfw.CallbackBridge
import kotlin.math.abs

/**
 * FCL-aligned mouse / key injection ([FCLInput] + [FCLBridge.pushEvent*]).
 *
 * Pointers are tracked in **view / screen space** (FCL cursorX/Y).
 * Game coords = viewCoords × scaleFactor, where
 * scaleFactor = windowWidth / screenWidth (FCL), never viewWidth==0/1.
 */
object GameInput {
    const val MOUSE_LEFT = 1000
    const val MOUSE_MIDDLE = 1001
    const val MOUSE_RIGHT = 1002
    const val MOUSE_SCROLL_UP = 1003
    const val MOUSE_SCROLL_DOWN = 1004

    private val MOUSE_MAP = mapOf(
        MOUSE_LEFT to GlfwKeys.MOUSE_LEFT,
        MOUSE_MIDDLE to GlfwKeys.MOUSE_MIDDLE,
        MOUSE_RIGHT to GlfwKeys.MOUSE_RIGHT,
    )

    @Volatile
    var cursorView: ImageView? = null

    /** FCL AndroidUtils.getScreenWidth/Height — stable, never 0 after init. */
    @Volatile
    private var screenWidth = 1
    @Volatile
    private var screenHeight = 1

    @Volatile
    private var touchPadWidth = 1
    @Volatile
    private var touchPadHeight = 1

    @Volatile
    var pointerX: Int = 0
        private set
    @Volatile
    var pointerY: Int = 0
        private set

    /** Cached density for cursor hotspot — avoid displayMetrics lookup every MOVE. */
    @Volatile
    private var cursorHotspot = 3f

    fun initScreenSize(widthPx: Int, heightPx: Int) {
        screenWidth = widthPx.coerceAtLeast(1)
        screenHeight = heightPx.coerceAtLeast(1)
        touchPadWidth = screenWidth
        touchPadHeight = screenHeight
        if (pointerX == 0 && pointerY == 0) {
            pointerX = screenWidth / 2
            pointerY = screenHeight / 2
        }
    }

    fun initScreenSizeFromDisplay() {
        val dm = Resources.getSystem().displayMetrics
        initScreenSize(dm.widthPixels, dm.heightPixels)
    }

    /**
     * FCL scaleFactor = gameWindow / screen.
     * Falls back to 1.0 when sizes look unset (avoids ×2400 blow-up).
     */
    fun scaleFactor(): Double {
        val sw = screenWidth.coerceAtLeast(1)
        val gw = CallbackBridge.windowWidth
        if (gw <= 1) return 1.0
        val scale = gw.toDouble() / sw.toDouble()
        if (scale < 0.05 || scale > 4.0) return 1.0
        return scale
    }

    fun bindCursor(view: ImageView?, padWidth: Int, padHeight: Int) {
        cursorView = view
        if (view != null) {
            cursorHotspot = view.resources.displayMetrics.density * 3f
        }
        if (padWidth > 1 && padHeight > 1) {
            touchPadWidth = padWidth
            touchPadHeight = padHeight
            screenWidth = padWidth
            screenHeight = padHeight
        }
        refreshCursorVisibility()
    }

    fun refreshCursorVisibility() {
        val cursor = cursorView ?: return
        cursor.visibility = if (CallbackBridge.isGrabbing()) View.GONE else View.VISIBLE
    }

    /** FCL FCLInput.setPointer — view/screen space, then × scaleFactor into GLFW. */
    fun setPointer(x: Int, y: Int) {
        val sw = screenWidth.coerceAtLeast(1)
        val sh = screenHeight.coerceAtLeast(1)
        val vx: Int
        val vy: Int
        if (CallbackBridge.isGrabbing()) {
            vx = x
            vy = y
        } else {
            vx = x.coerceIn(0, sw)
            vy = y.coerceIn(0, sh)
        }
        pointerX = vx
        pointerY = vy

        // FCL updates overlay cursor immediately (no Choreographer defer / bringToFront).
        if (!CallbackBridge.isGrabbing()) {
            applyCursorView(vx, vy)
        }

        val scale = scaleFactor()
        CallbackBridge.sendCursorPos((vx * scale).toFloat(), (vy * scale).toFloat())
    }

    fun setPointer(x: Float, y: Float) {
        setPointer(x.toInt(), y.toInt())
    }

    private fun applyCursorView(viewX: Int, viewY: Int) {
        val cursor = cursorView ?: return
        if (CallbackBridge.isGrabbing()) {
            cursor.visibility = View.GONE
            return
        }
        if (cursor.visibility != View.VISIBLE) {
            cursor.visibility = View.VISIBLE
        }
        cursor.translationX = viewX - cursorHotspot
        cursor.translationY = viewY - cursorHotspot
    }

    fun sendKeyEvent(keycode: Int, press: Boolean) {
        val mouseBtn = MOUSE_MAP[keycode]
        when {
            mouseBtn != null -> CallbackBridge.sendMouseButton(mouseBtn, press)
            keycode == MOUSE_SCROLL_UP -> if (press) CallbackBridge.sendScroll(0.0, 1.0)
            keycode == MOUSE_SCROLL_DOWN -> if (press) CallbackBridge.sendScroll(0.0, -1.0)
            else -> CallbackBridge.sendKey(keycode, press)
        }
    }

    fun sendKeyTap(keycode: Int) {
        sendKeyEvent(keycode, true)
        sendKeyEvent(keycode, false)
    }

    fun releaseAllMouseButtons() {
        CallbackBridge.sendMouseButton(GlfwKeys.MOUSE_LEFT, false)
        CallbackBridge.sendMouseButton(GlfwKeys.MOUSE_RIGHT, false)
        CallbackBridge.sendMouseButton(GlfwKeys.MOUSE_MIDDLE, false)
    }

    fun handleExternalMouseEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val press = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                when (event.actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> sendKeyEvent(MOUSE_LEFT, press)
                    MotionEvent.BUTTON_SECONDARY -> sendKeyEvent(MOUSE_RIGHT, press)
                    MotionEvent.BUTTON_TERTIARY -> sendKeyEvent(MOUSE_MIDDLE, press)
                }
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val steps = abs(v.toInt()).coerceAtLeast(if (v != 0f) 1 else 0)
                val code = if (v > 0f) MOUSE_SCROLL_UP else MOUSE_SCROLL_DOWN
                repeat(steps) { sendKeyEvent(code, true) }
            }
        }
        return true
    }

    /**
     * FCL GameMenu TouchPad OnGenericMotionListener path:
     * HOVER_MOVE → setPointer; BUTTON_PRESS/SCROLL → handleExternalMouseEvent.
     */
    fun handleGenericMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            !event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        ) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                if (!CallbackBridge.isGrabbing()) {
                    setPointer(event.rawX.toInt(), event.rawY.toInt())
                }
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE,
            MotionEvent.ACTION_SCROLL -> {
                handleExternalMouseEvent(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (CallbackBridge.isGrabbing()) {
                    setPointer(pointerX + event.x.toInt(), pointerY + event.y.toInt())
                } else {
                    setPointer(event.x.toInt(), event.y.toInt())
                }
                return true
            }
        }
        return false
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        val source = device.sources
        val fromMouse =
            (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                (source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
        if (!fromMouse) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            sendKeyEvent(MOUSE_RIGHT, event.action == KeyEvent.ACTION_DOWN)
            return true
        }
        return false
    }
}
