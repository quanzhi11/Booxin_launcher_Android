package com.booxin.launcher.ui.launch.input

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import com.booxin.runtime.BooxinBridge
import kotlin.math.abs

/** 全屏触控转鼠标。 */
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
    private var tapAnchorX = 0
    private var tapAnchorY = 0
    private var tapCancelled = false

    private var clickDownPending = false
    private var guiLmbHeld = false
    private var grabbedTapUp: Choreographer.FrameCallback? = null
    private var grabbedTapButton: Int = -1

    private var combinedLongPressArmed = false
    private var combinedDigHeld = false
    private val combinedLongPressFrame: Choreographer.FrameCallback = Choreographer.FrameCallback {
        if (!combinedLongPressArmed || tapCancelled || !shouldBeDown) return@FrameCallback
        combinedLongPressArmed = false
        combinedDigHeld = true
        GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, true)
    }

    private val choreographer: Choreographer get() = BooxinBridge.sChoreographer

    private val clickDownFrame: Choreographer.FrameCallback = Choreographer.FrameCallback {
        clickDownPending = false
        GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, true)
        guiLmbHeld = true
    }

    private val clickUpFrame: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // 快点：UP 早于 DOWN，补发 DOWN 再抬起。
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
        // 不要取消待发的 UP，否则 LMB 会卡住。
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

    init {
        isClickable = true
        isFocusable = false
    }

    fun syncCursorToCenter() {
        GameInput.setPointer(width.coerceAtLeast(1) / 2, height.coerceAtLeast(1) / 2)
    }

    fun resetTouchState() {
        cancelCombinedLongPress(releaseDig = true)
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
                    // Already tracking a GUI finger; ignore extra pointers.
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
                // TouchPassthroughLayout forwards each finger as a single-pointer stream
                // (always ACTION_DOWN). Allow starting look while a virtual key is held.
                val idx = event.actionIndex
                val x = event.getX(idx)
                val screenW = width.coerceAtLeast(1)
                if (disableLeftTouchWhenGrabbed && x <= screenW / 2f) {
                    return true
                }
                // New look finger replaces a previous one from this pad.
                beginLook(event.getPointerId(idx), x.toInt(), event.getY(idx).toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!shouldBeDown || pointerId < 0) return true
                val idx = event.findPointerIndex(pointerId)
                if (idx < 0) {
                    // Synthesized events always use pointer 0; recover if ids diverge.
                    if (event.pointerCount > 0) {
                        val x = event.getX(0).toInt()
                        val y = event.getY(0).toInt()
                        applyLookDelta(x, y)
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
            cancelCombinedLongPress(releaseDig = true)
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
        cancelCombinedLongPress(releaseDig = true)
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
        maybeArmCombinedLongPressDig()
    }

    private fun maybeArmCombinedLongPressDig() {
        if (disableGesture || gestureMode != GestureMode.COMBINED) return
        val snap = GestureContext.snapshot()
        if (!snap.preferLongPressDig) return
        combinedLongPressArmed = true
        choreographer.postFrameCallbackDelayed(combinedLongPressFrame, LONG_PRESS_MS)
    }

    private fun cancelCombinedLongPress(releaseDig: Boolean) {
        if (combinedLongPressArmed) {
            choreographer.removeFrameCallback(combinedLongPressFrame)
            combinedLongPressArmed = false
        }
        if (releaseDig && combinedDigHeld) {
            GameInput.sendKeyEvent(GameInput.MOUSE_LEFT, false)
            combinedDigHeld = false
        }
    }

    private fun endLook(allowTap: Boolean) {
        shouldBeDown = false
        val digWasHeld = combinedDigHeld
        cancelCombinedLongPress(releaseDig = true)
        if (allowTap && !tapCancelled && !disableGesture && !digWasHeld) {
            // Stationary short tap = one click by current mode; keep down ≥1 frame.
            val button = when (gestureMode) {
                GestureMode.BUILD -> GameInput.MOUSE_RIGHT
                GestureMode.FIGHT -> GameInput.MOUSE_LEFT
                GestureMode.COMBINED -> {
                    val snap = GestureContext.snapshot()
                    val btn = GestureContext.resolveCombinedTapButton(snap)
                    val label = if (btn == GameInput.MOUSE_RIGHT) "RMB" else "LMB"
                    Log.e("BooxinGesture", "COMBINED tap hit=${snap.hit} held=${snap.held} btn=$label")
                    if (combinedTapToastLeft > 0) {
                        combinedTapToastLeft--
                        Toast.makeText(
                            context,
                            "综合 hit=${snap.hit} held=${snap.held} → $label",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    btn
                }
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
        private const val LONG_PRESS_MS = 650L
        private var combinedTapToastLeft = 8
    }
}
