package com.booxin.launcher.ui.launch.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.lwjgl.glfw.CallbackBridge
import kotlin.math.abs

/**
 * Full-screen touch → mouse, matching FCL [TouchPad] behavior.
 *
 * GUI (cursor enabled / not grabbing):
 * - [MouseMoveMode.CLICK]: view coords → pointer; delayed LMB (33ms)
 * - [MouseMoveMode.SLIDE]: drag moves cursor; short tap = LMB
 *
 * Grabbed (in-world):
 * - Drag looks (view-space pointer + scaleFactor, same as FCL)
 * - Short tap / long-press follow [GestureMode]
 */
class GameTouchPad @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var mouseMoveMode: MouseMoveMode = MouseMoveMode.CLICK
    var gestureMode: GestureMode = GestureMode.BUILD
    var disableGesture: Boolean = false
    var lookSensitivity: Float = 1.0f
    var guiSensitivity: Float = 1.0f
    var disableLeftTouchWhenGrabbed: Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    private var downX = 0
    private var downY = 0
    private var initialX = 0
    private var initialY = 0
    private var downTime = 0L
    private var pointerId = -1
    private var holdingLeft = false
    private var holdingRight = false
    private var lastPointerCount = 0
    private var shouldBeDown = false

    private val longPress = Runnable {
        if (disableGesture) return@Runnable
        if (gestureMode == GestureMode.BUILD) {
            GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, true)
            holdingLeft = true
        } else {
            GameInput.sendKeyEvent(GameInput.MOUSE_RIGHT, true)
            holdingRight = true
        }
    }

    /** FCL: schedule down and up independently with the same 33ms delay. */
    private fun scheduleClickDown() {
        CallbackBridge.sChoreographer.removeFrameCallback(clickDownFrame)
        CallbackBridge.sChoreographer.postFrameCallbackDelayed(clickDownFrame, CLICK_FRAME_DELAY_MS)
    }

    private fun scheduleClickUp() {
        CallbackBridge.sChoreographer.removeFrameCallback(clickUpFrame)
        CallbackBridge.sChoreographer.postFrameCallbackDelayed(clickUpFrame, CLICK_FRAME_DELAY_MS)
    }

    private val clickDownFrame = android.view.Choreographer.FrameCallback {
        GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, true)
    }
    private val clickUpFrame = android.view.Choreographer.FrameCallback {
        GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, false)
    }

    init {
        isClickable = true
        isFocusable = false
    }

    fun syncCursorToCenter() {
        GameInput.setPointer(width.coerceAtLeast(1) / 2, height.coerceAtLeast(1) / 2)
    }

    fun resetTouchState() {
        handler.removeCallbacks(longPress)
        CallbackBridge.sChoreographer.removeFrameCallback(clickDownFrame)
        CallbackBridge.sChoreographer.removeFrameCallback(clickUpFrame)
        if (holdingLeft || holdingRight) {
            GameInput.releaseAllMouseButtons()
        }
        holdingLeft = false
        holdingRight = false
        pointerId = -1
        shouldBeDown = false
        lastPointerCount = 0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        return if (CallbackBridge.isGrabbing()) {
            handleGrabbed(event)
        } else {
            handleGui(event)
        }
    }

    private fun handleGui(event: MotionEvent): Boolean {
        // FCL TouchPad: external mouse only updates pointer on MOVE here;
        // clicks/hover come from OnGenericMotionListener (LaunchActivity).
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.action == MotionEvent.ACTION_MOVE) {
                GameInput.setPointer(event.x.toInt(), event.y.toInt())
            }
            return true
        }

        if (mouseMoveMode == MouseMoveMode.SLIDE) {
            return handleGuiSlide(event)
        }

        // FCL CLICK mode: set cursor every event; delay LMB down/up ~33ms.
        GameInput.setPointer(event.x.toInt(), event.y.toInt())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                CallbackBridge.sChoreographer.removeFrameCallback(clickUpFrame)
                scheduleClickDown()
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(pointerId).takeIf { it >= 0 } ?: 0
                GameInput.setPointer(event.getX(idx).toInt(), event.getY(idx).toInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scheduleClickUp()
                pointerId = -1
            }
        }
        return true
    }

    private fun handleGuiSlide(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                downX = event.x.toInt()
                downY = event.y.toInt()
                downTime = System.currentTimeMillis()
                initialX = GameInput.pointerX
                initialY = GameInput.pointerY
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(pointerId).takeIf { it >= 0 } ?: 0
                val x = event.getX(idx).toInt()
                val y = event.getY(idx).toInt()
                val deltaX = ((x - downX) * guiSensitivity).toInt()
                val deltaY = ((y - downY) * guiSensitivity).toInt()
                val maxX = width.coerceAtLeast(1)
                val maxY = height.coerceAtLeast(1)
                GameInput.setPointer(
                    (initialX + deltaX).coerceIn(0, maxX),
                    (initialY + deltaY).coerceIn(0, maxY)
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (System.currentTimeMillis() - downTime <= TAP_MS &&
                    abs(event.x - downX) <= TAP_SLOP &&
                    abs(event.y - downY) <= TAP_SLOP
                ) {
                    GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, true)
                    GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, false)
                }
                pointerId = -1
            }
        }
        return true
    }

    private fun handleGrabbed(event: MotionEvent): Boolean {
        // FCL: ignore finger-path for external mouse while grabbing;
        // relative look / buttons handled via generic motion + key events.
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return true
        }
        val screenW = width.coerceAtLeast(1)
        if (disableLeftTouchWhenGrabbed && event.x <= screenW / 2f) {
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                shouldBeDown = true
                downX = event.x.toInt()
                downY = event.y.toInt()
                downTime = System.currentTimeMillis()
                initialX = GameInput.pointerX
                initialY = GameInput.pointerY
                holdingLeft = false
                holdingRight = false
                handler.postDelayed(longPress, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerCount = event.pointerCount
                var idx = event.findPointerIndex(pointerId)
                if (idx < 0 || lastPointerCount != pointerCount || !shouldBeDown) {
                    shouldBeDown = true
                    pointerId = event.getPointerId(0)
                    downX = event.x.toInt()
                    downY = event.y.toInt()
                    initialX = GameInput.pointerX
                    initialY = GameInput.pointerY
                    lastPointerCount = pointerCount
                    return true
                }
                val newDownX = event.getX(idx).toInt()
                val newDownY = event.getY(idx).toInt()
                // FCL: delta * sensitivity / scaleFactor, then setPointer (× scale again).
                val scale = GameInput.scaleFactor().coerceAtLeast(0.0001)
                val deltaX = ((newDownX - downX) * lookSensitivity / scale).toInt()
                val deltaY = ((newDownY - downY) * lookSensitivity / scale).toInt()
                if ((abs(deltaX) > 1 || abs(deltaY) > 1) &&
                    System.currentTimeMillis() - downTime < LONG_PRESS_MS
                ) {
                    handler.removeCallbacks(longPress)
                }
                GameInput.setPointer(initialX + deltaX, initialY + deltaY)
                // FCL advances downX each move (incremental), and refreshes initial from pointer.
                downX = newDownX
                downY = newDownY
                initialX = GameInput.pointerX
                initialY = GameInput.pointerY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                shouldBeDown = false
                when {
                    holdingLeft -> GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, false)
                    holdingRight -> GameInput.sendKeyEvent(GameInput.MOUSE_RIGHT, false)
                    System.currentTimeMillis() - downTime <= TAP_MS &&
                        abs(event.x - downX) <= TAP_SLOP &&
                        abs(event.y - downY) <= TAP_SLOP -> {
                        if (!disableGesture) {
                            if (gestureMode == GestureMode.BUILD) {
                                GameInput.sendKeyEvent(GameInput.MOUSE_RIGHT, true)
                                GameInput.sendKeyEvent(GameInput.MOUSE_RIGHT, false)
                            } else {
                                GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, true)
                                GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, false)
                            }
                        }
                    }
                }
                holdingLeft = false
                holdingRight = false
                pointerId = -1
            }
        }
        lastPointerCount = event.pointerCount
        return true
    }

    companion object {
        private const val LONG_PRESS_MS = 400L
        private const val CLICK_FRAME_DELAY_MS = 33L
        private const val TAP_MS = 100L
        private const val TAP_SLOP = 10
    }
}
