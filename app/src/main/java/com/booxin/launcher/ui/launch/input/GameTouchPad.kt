package com.booxin.launcher.ui.launch.input

import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.booxin.runtime.BooxinBridge
import kotlin.math.abs

/**
 * Full-screen touch â†?mouse, matching FCL [TouchPad] behavior.
 *
 * Grabbed (in-world): drag looks; stationary release clicks by [gestureMode]
 * (BUILD = RMB, FIGHT = LMB). No long-press gesture.
 *
 * Supports starting look on [MotionEvent.ACTION_POINTER_DOWN] so a second finger
 * can rotate the camera while a virtual key is held on [TouchPassthroughLayout].
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

    private var downX = 0
    private var downY = 0
    private var initialX = 0
    private var initialY = 0
    private var downTime = 0L
    private var pointerId = -1
    private var shouldBeDown = false
    /** Finger position at look start â€?used to decide tap vs look. */
    private var tapAnchorX = 0
    private var tapAnchorY = 0
    private var tapCancelled = false

    /** FCL: schedule down and up independently with the same 33ms delay. */
    private fun scheduleClickDown() {
        BooxinBridge.sChoreographer.removeFrameCallback(clickDownFrame)
        BooxinBridge.sChoreographer.postFrameCallbackDelayed(clickDownFrame, CLICK_FRAME_DELAY_MS)
    }

    private fun scheduleClickUp() {
        BooxinBridge.sChoreographer.removeFrameCallback(clickUpFrame)
        BooxinBridge.sChoreographer.postFrameCallbackDelayed(clickUpFrame, CLICK_FRAME_DELAY_MS)
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
        BooxinBridge.sChoreographer.removeFrameCallback(clickDownFrame)
        BooxinBridge.sChoreographer.removeFrameCallback(clickUpFrame)
        pointerId = -1
        shouldBeDown = false
        tapCancelled = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        return if (BooxinBridge.isGrabbing()) {
            handleGrabbed(event)
        } else {
            handleGui(event)
        }
    }

    private fun handleGui(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.action == MotionEvent.ACTION_MOVE) {
                GameInput.setPointer(event.x.toInt(), event.y.toInt())
            }
            return true
        }

        if (mouseMoveMode == MouseMoveMode.SLIDE) {
            return handleGuiSlide(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerId >= 0 && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    // Already tracking a GUI finger; ignore extra pointers.
                    return true
                }
                val idx = event.actionIndex
                pointerId = event.getPointerId(idx)
                GameInput.setPointer(event.getX(idx).toInt(), event.getY(idx).toInt())
                BooxinBridge.sChoreographer.removeFrameCallback(clickUpFrame)
                scheduleClickDown()
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(pointerId).takeIf { it >= 0 } ?: return true
                GameInput.setPointer(event.getX(idx).toInt(), event.getY(idx).toInt())
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    scheduleClickUp()
                    pointerId = -1
                }
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
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerId >= 0 && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    return true
                }
                val idx = event.actionIndex
                pointerId = event.getPointerId(idx)
                downX = event.getX(idx).toInt()
                downY = event.getY(idx).toInt()
                downTime = System.currentTimeMillis()
                initialX = GameInput.pointerX
                initialY = GameInput.pointerY
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(pointerId).takeIf { it >= 0 } ?: return true
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
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    finishGuiSlideTap(event.actionIndex, event)
                    pointerId = -1
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointerId >= 0) {
                    val idx = event.findPointerIndex(pointerId).takeIf { it >= 0 } ?: 0
                    finishGuiSlideTap(idx, event)
                }
                pointerId = -1
            }
        }
        return true
    }

    private fun finishGuiSlideTap(idx: Int, event: MotionEvent) {
        val x = event.getX(idx)
        val y = event.getY(idx)
        if (System.currentTimeMillis() - downTime <= TAP_MS &&
            abs(x - downX) <= TAP_SLOP &&
            abs(y - downY) <= TAP_SLOP
        ) {
            GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, true)
            GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, false)
        }
    }

    private fun handleGrabbed(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Start (or retarget) look on this pointer. Prefer a new finger when
                // one is already held on a virtual key and this event is POINTER_DOWN.
                val idx = event.actionIndex
                val x = event.getX(idx)
                val screenW = width.coerceAtLeast(1)
                if (disableLeftTouchWhenGrabbed && x <= screenW / 2f) {
                    return true
                }
                if (pointerId >= 0 && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    // Primary down replaces previous look finger.
                } else if (pointerId >= 0 && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    // Already looking with another finger â€?ignore extra look fingers.
                    return true
                }
                beginLook(event.getPointerId(idx), x.toInt(), event.getY(idx).toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!shouldBeDown || pointerId < 0) return true
                val idx = event.findPointerIndex(pointerId)
                if (idx < 0) return true
                val newDownX = event.getX(idx).toInt()
                val newDownY = event.getY(idx).toInt()
                if (!tapCancelled &&
                    (abs(newDownX - tapAnchorX) > TAP_SLOP || abs(newDownY - tapAnchorY) > TAP_SLOP)
                ) {
                    tapCancelled = true
                }
                val scale = GameInput.scaleFactor().coerceAtLeast(0.0001)
                val deltaX = ((newDownX - downX) * lookSensitivity / scale).toInt()
                val deltaY = ((newDownY - downY) * lookSensitivity / scale).toInt()
                GameInput.setPointer(initialX + deltaX, initialY + deltaY)
                downX = newDownX
                downY = newDownY
                initialX = GameInput.pointerX
                initialY = GameInput.pointerY
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    endLook(allowTap = true)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pointerId >= 0) endLook(allowTap = true)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pointerId >= 0) {
                    endLook(allowTap = false)
                } else {
                    shouldBeDown = false
                    tapCancelled = false
                }
            }
        }
        return true
    }

    private fun beginLook(id: Int, x: Int, y: Int) {
        pointerId = id
        shouldBeDown = true
        downX = x
        downY = y
        tapAnchorX = x
        tapAnchorY = y
        tapCancelled = false
        downTime = System.currentTimeMillis()
        initialX = GameInput.pointerX
        initialY = GameInput.pointerY
    }

    private fun endLook(allowTap: Boolean) {
        shouldBeDown = false
        if (allowTap && !tapCancelled && !disableGesture) {
            // No long-press: stationary touch-up = one click by current mode.
            when (gestureMode) {
                GestureMode.BUILD -> {
                    GameInput.sendKeyEvent(GameInput.MOUSE_RIGHT, true)
                    GameInput.sendKeyEvent(GameInput.MOUSE_RIGHT, false)
                }
                GestureMode.FIGHT -> {
                    GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, true)
                    GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, false)
                }
            }
        }
        pointerId = -1
        tapCancelled = false
    }

    companion object {
        private const val CLICK_FRAME_DELAY_MS = 33L
        private const val TAP_MS = 100L
        private const val TAP_SLOP = 12
    }
}
