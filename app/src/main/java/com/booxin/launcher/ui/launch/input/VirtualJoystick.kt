package com.booxin.launcher.ui.launch.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Left-side virtual WASD stick (mobile-style movement pad).
 * In edit mode it can be dragged / resized like other controls.
 */
class VirtualJoystick @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var spec: ControlLayoutData.JoystickSpec = ControlLayoutData.JoystickSpec()
        private set

    var editMode: Boolean = false
        set(value) {
            field = value
            if (!value) releaseAll()
            invalidate()
        }

    var editSelected: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onSelect: (() -> Unit)? = null
    var onMoved: ((ControlLayoutData.JoystickSpec) -> Unit)? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = 0x88FFFFFF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33000000
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x44FFFFFF.toInt()
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCCFFFFFF.toInt()
    }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xAA1B6CA8.toInt()
    }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF4FC3F7.toInt()
    }

    private val knob = PointF()
    private var keyW = false
    private var keyA = false
    private var keyS = false
    private var keyD = false
    private var active = false
    private var pointerId = -1

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        knob.set(w / 2f, h / 2f)
    }

    fun applySpec(newSpec: ControlLayoutData.JoystickSpec) {
        spec = newSpec
        val parent = parent as? ViewGroup
        if (parent != null && parent.width > 0 && parent.height > 0) {
            layoutInParent(parent.width, parent.height)
        }
        invalidate()
    }

    fun layoutInParent(parentW: Int, parentH: Int) {
        if (parentW <= 0 || parentH <= 0) return
        val sizePx = dp(spec.sizeDp)
        val lp = (layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(sizePx, sizePx).also { layoutParams = it }
        lp.width = sizePx
        lp.height = sizePx
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = (spec.x * parentW - sizePx / 2f).toInt()
            .coerceIn(0, (parentW - sizePx).coerceAtLeast(0))
        lp.topMargin = (spec.y * parentH - sizePx / 2f).toInt()
            .coerceIn(0, (parentH - sizePx).coerceAtLeast(0))
        layoutParams = lp
    }

    /** Joystick bounds in [target] coordinates (for look-exclusion). */
    fun exclusionRectIn(target: View): RectF {
        val locJ = IntArray(2)
        val locT = IntArray(2)
        getLocationOnScreen(locJ)
        target.getLocationOnScreen(locT)
        val l = (locJ[0] - locT[0]).toFloat()
        val t = (locJ[1] - locT[1]).toFloat()
        return RectF(l, t, l + width, t + height)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.92f
        fillPaint.color = if (active) 0x661B6CA8 else 0x33000000
        ringPaint.color = if (active) 0xCCFFFFFF.toInt() else 0x88FFFFFF.toInt()
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, radius * 0.42f, crossPaint)
        canvas.drawLine(cx - radius * 0.72f, cy, cx + radius * 0.72f, cy, crossPaint)
        canvas.drawLine(cx, cy - radius * 0.72f, cx, cy + radius * 0.72f, crossPaint)
        val kr = if (active) radius * 0.38f else radius * 0.32f
        knobPaint.color = if (active) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
        canvas.drawCircle(knob.x, knob.y, kr, knobPaint)
        canvas.drawCircle(knob.x, knob.y, kr, knobRing)
        if (editMode && editSelected) {
            canvas.drawCircle(cx, cy, radius, selectPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return handleEdit(event)
        return handlePlay(event)
    }

    private fun handlePlay(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(cx, cy) * 0.92f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                applyStick(event.getX(0), event.getY(0), cx, cy, maxR)
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(pointerId)
                if (idx < 0) return true
                applyStick(event.getX(idx), event.getY(idx), cx, cy, maxR)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    resetStick(cx, cy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetStick(cx, cy)
            }
        }
        return true
    }

    private fun applyStick(x: Float, y: Float, cx: Float, cy: Float, maxR: Float) {
        active = true
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx, dy)
        if (dist <= maxR) {
            knob.set(x, y)
        } else {
            val s = maxR / dist
            knob.set(cx + dx * s, cy + dy * s)
        }
        updateKeys(dx / maxR, dy / maxR)
        invalidate()
    }

    private fun resetStick(cx: Float, cy: Float) {
        pointerId = -1
        active = false
        knob.set(cx, cy)
        releaseAll()
        invalidate()
    }

    private fun handleEdit(event: MotionEvent): Boolean {
        val parent = parent as? ViewGroup ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onSelect?.invoke()
                downRawX = event.rawX
                downRawY = event.rawY
                startX = spec.x
                startY = spec.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > 8f || abs(dy) > 8f)) dragging = true
                if (dragging && parent.width > 0 && parent.height > 0) {
                    val nx = (startX + dx / parent.width).coerceIn(0.08f, 0.92f)
                    val ny = (startY + dy / parent.height).coerceIn(0.12f, 0.92f)
                    spec = spec.copy(x = nx, y = ny)
                    layoutInParent(parent.width, parent.height)
                    onMoved?.invoke(spec)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return true
    }

    private fun updateKeys(nx: Float, ny: Float) {
        // FCL ControlDirection: atan2(dy, dx) mapped to 0..360, 8 sectors of 45°.
        val dead = 0.28f
        val mag = hypot(nx, ny)
        var w = false
        var a = false
        var s = false
        var d = false
        if (mag >= dead) {
            var deg = Math.toDegrees(atan2(ny.toDouble(), nx.toDouble()))
            if (deg < 0) deg += 360.0
            when {
                deg < 22.5 || deg >= 337.5 -> d = true
                deg < 67.5 -> {
                    s = true; d = true
                }
                deg < 112.5 -> s = true
                deg < 157.5 -> {
                    s = true; a = true
                }
                deg < 202.5 -> a = true
                deg < 247.5 -> {
                    w = true; a = true
                }
                deg < 292.5 -> w = true
                else -> {
                    w = true; d = true
                }
            }
        }
        setKey(GlfwKeys.KEY_W, w, keyW).also { keyW = it }
        setKey(GlfwKeys.KEY_A, a, keyA).also { keyA = it }
        setKey(GlfwKeys.KEY_S, s, keyS).also { keyS = it }
        setKey(GlfwKeys.KEY_D, d, keyD).also { keyD = it }
    }

    private fun setKey(key: Int, want: Boolean, was: Boolean): Boolean {
        if (want == was) return was
        GameInput.sendKeyEvent(key, want)
        return want
    }

    private fun releaseAll() {
        if (keyW) GameInput.sendKeyEvent(GlfwKeys.KEY_W, false)
        if (keyA) GameInput.sendKeyEvent(GlfwKeys.KEY_A, false)
        if (keyS) GameInput.sendKeyEvent(GlfwKeys.KEY_S, false)
        if (keyD) GameInput.sendKeyEvent(GlfwKeys.KEY_D, false)
        keyW = false
        keyA = false
        keyS = false
        keyD = false
    }

    fun releaseKeys() {
        pointerId = -1
        releaseAll()
        active = false
        if (width > 0 && height > 0) {
            knob.set(width / 2f, height / 2f)
            invalidate()
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).toInt()
}
