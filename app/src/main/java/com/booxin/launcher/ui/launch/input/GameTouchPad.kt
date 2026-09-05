package com.booxin.launcher.ui.launch.input

import android.content.Context
import android.util.AttributeSet
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.booxin.runtime.BooxinBridge
import kotlin.math.abs

/**
 * Full-screen touch to mouse.
 *
 * Grabbed: drag looks; short tap clicks by [gestureMode]
 * (COMBINED = RMB, FIGHT = LMB); long-press holds LMB (mine/attack).
 * Second finger can look while a virtual key is held.
 *
 * GUI clicks are frame-scheduled so cursor is committed before button down,
 * and pending up/down never cancel each other (stuck LMB / missed clicks).
 */
class GameTouchPad @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var mouseMoveMode: MouseMoveMode = MouseMoveMode.CLICK
    var gestureMode: GestureMode = GestureMode.COMBINED
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
    /** Finger position at look start — used to decide tap vs look. */
    private var tapAnchorX = 0
    private var tapAnchorY = 0
    private var tapCancelled = false

    /** True after DOWN callback posted, until it fires or is flushed. */
    private var clickDownPending = false
    /** True while LMB is logically held from GUI click scheduling. */
    private var guiLmbHeld = false
    /** Pending grabbed-mode tap release (COMBINED/FIGHT short tap). */
    private var grabbedTapUp: Choreographer.FrameCallback? = null
    private var grabbedTapButton: Int = -1
    /** True while LMB is held from an in-world long-press. */
    private var grabbedLongPressLmb = false

    private val choreographer: Choreographer get() = BooxinBridge.sChoreographer

    private val longPressRunnable = Runnable {
        if (!shouldBeDown || tapCancelled || disableGesture || pointerId < 0) return@Runnable
        cancelGrabbedTap(releaseIfHeld = true)
        grabbedLongPressLmb = true
        GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, true)
    }

    private val clickDownFrame: Choreographer.FrameCallback = Choreographer.FrameCallback {
        clickDownPending = false
        GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, true)
        guiLmbHeld = true
    }

    private val clickUpFrame: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // Fast tap: UP arrived before delayed DOWN — force DOWN then release next frame.
            if (clickDownPending) {
                choreographer.removeFrameCallback(clickDownFrame)
                clickDownPending = false
                GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, true)
                guiLmbHeld = true
                choreographer.postFrameCallbackDelayed(this, CLICK_FRAME_DELAY_MS)
                return
            }
            if (guiLmbHeld) {
                GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, false)
                guiLmbHeld = false
            }
        }
    }

    private fun scheduleClickDown() {
        // Never cancel a pending UP — that left LMB stuck in the game.
        flushGuiClick(releaseIfHeld = true)
        clickDownPending = true
        choreographer.postFrameCallbackDelayed(clickDownFrame, CLICK_FRAME_DELAY_MS)
    }

    private fun scheduleClickUp() {
        choreographer.removeFrameCallback(clickUpFrame)
        choreographer.postFrameCallbackDelayed(clickUpFrame, CLICK_FRAME_DELAY_MS)
    }

    /** Cancel pending frames; optionally send UP if button is held. */
    private fun flushGuiClick(releaseIfHeld: Boolean) {
        choreographer.removeFrameCallback(clickDownFrame)
        choreographer.removeFrameCallback(clickUpFrame)
        clickDownPending = false
        if (releaseIfHeld && guiLmbHeld) {
            GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, false)
            guiLmbHeld = false
        }
    }

    private fun scheduleGrabbedTap(buttonCode: Int) {
        cancelGrabbedTap(releaseIfHeld = true)
        grabbedTapButton = buttonCode
        GameInput.sendKeyEvent(buttonCode, true)
        val cb: Choreographer.FrameCallback = Choreographer.FrameCallback {
            if (grabbedTapButton == buttonCode) {
                GameInput.sendKeyEvent(buttonCode, false)
                grabbedTapButton = -1
            }
            grabbedTapUp = null
        }
        grabbedTapUp = cb
        choreographer.postFrameCallbackDelayed(cb, CLICK_FRAME_DELAY_MS)
    }

    private fun cancelGrabbedTap(releaseIfHeld: Boolean) {
        grabbedTapUp?.let { choreographer.removeFrameCallback(it) }
        grabbedTapUp = null
        if (releaseIfHeld && grabbedTapButton >= 0) {
            GameInput.sendKeyEvent(grabbedTapButton, false)
        }
        grabbedTapButton = -1
    }

    private fun cancelLongPress(releaseIfHeld: Boolean) {
        removeCallbacks(longPressRunnable)
        if (releaseIfHeld && grabbedLongPressLmb) {
            GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, false)
            grabbedLongPressLmb = false
        }
    }

    init {
        isClickable = true
        isFocusable = false
    }

    fun syncCursorToCenter() {
        GameInput.setPointer(width.coerceAtLeast(1) / 2, height.coerceAtLeast(1) / 2)
    }

    fun resetTouchState() {
        cancelLongPress(releaseIfHeld = true)
        cancelGrabbedTap(releaseIfHeld = true)
        flushGuiClick(releaseIfHeld = true)
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
                    return true
                }
                val idx = event.actionIndex
                pointerId = event.getPointerId(idx)
                GameInput.setPointer(event.getX(idx).toInt(), event.getY(idx).toInt())
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
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    flushGuiClick(releaseIfHeld = true)
                } else {
                    scheduleClickUp()
                }
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
                if (pointerId >= 0 && event.actionMasked != MotionEvent.ACTION_CANCEL) {
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
            scheduleGrabbedTap(GameInput.MOUSE_LEFT)
        }
    }

    private fun handleGrabbed(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val x = event.getX(idx)
                val screenW = width.coerceAtLeast(1)
                if (disableLeftTouchWhenGrabbed && x <= screenW / 2f) {
                    return true
                }
                beginLook(event.getPointerId(idx), x.toInt(), event.getY(idx).toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!shouldBeDown || pointerId < 0) return true
                val idx = event.findPointerIndex(pointerId)
                if (idx < 0) {
                    if (event.pointerCount > 0) {
                        applyLookDelta(event.getX(0).toInt(), event.getY(0).toInt())
                    }
                    return true
                }
                applyLookDelta(event.getX(idx).toInt(), event.getY(idx).toInt())
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
                    cancelLongPress(releaseIfHeld = true)
                    shouldBeDown = false
                    tapCancelled = false
                }
            }
        }
        return true
    }

    private fun applyLookDelta(newDownX: Int, newDownY: Int) {
        if (!tapCancelled &&
            (abs(newDownX - tapAnchorX) > TAP_SLOP || abs(newDownY - tapAnchorY) > TAP_SLOP)
        ) {
            tapCancelled = true
            // Looking around cancels a pending long-press arm, but keeps an
            // already-active LMB hold so you can mine while adjusting aim.
            if (!grabbedLongPressLmb) {
                removeCallbacks(longPressRunnable)
            }
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

    private fun beginLook(id: Int, x: Int, y: Int) {
        cancelLongPress(releaseIfHeld = true)
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
        if (!disableGesture) {
            removeCallbacks(longPressRunnable)
            postDelayed(longPressRunnable, LONG_PRESS_MS)
        }
    }

    private fun endLook(allowTap: Boolean) {
        shouldBeDown = false
        removeCallbacks(longPressRunnable)
        if (grabbedLongPressLmb) {
            GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, false)
            grabbedLongPressLmb = false
            pointerId = -1
            tapCancelled = false
            return
        }
        if (allowTap && !tapCancelled && !disableGesture) {
            // 综合：短按=右键；战斗：短按=左键。长按均为按住左键。
            val button = when (gestureMode) {
                GestureMode.COMBINED -> GameInput.MOUSE_RIGHT
                GestureMode.FIGHT -> GameInput.MOUSE_LEFT
            }
            scheduleGrabbedTap(button)
        }
        pointerId = -1
        tapCancelled = false
    }

    companion object {
        private const val CLICK_FRAME_DELAY_MS = 33L
        private const val TAP_MS = 100L
        private const val TAP_SLOP = 12
        /** Hold this long without sliding → LMB down until finger up. */
        private const val LONG_PRESS_MS = 400L
    }
}
