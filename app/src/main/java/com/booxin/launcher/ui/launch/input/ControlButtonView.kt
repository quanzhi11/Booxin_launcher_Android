package com.booxin.launcher.ui.launch.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.booxin.launcher.R
import kotlin.math.abs

/**
 * A single virtual control: play-mode injects GLFW; edit-mode can be dragged / selected.
 */
@SuppressLint("ViewConstructor")
class ControlButtonView(
    context: Context,
    var spec: ControlButtonSpec
) : TextView(context) {

    var editMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                setPlayPressed(false)
                releaseHold()
            }
            refreshChrome()
        }

    var editSelected: Boolean = false
        set(value) {
            field = value
            refreshChrome()
        }

    var onSelect: ((ControlButtonView) -> Unit)? = null
    var onMoved: ((ControlButtonView, Float, Float) -> Unit)? = null

    private var holding = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    init {
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        applySpec(spec)
        refreshChrome()
    }

    fun applySpec(newSpec: ControlButtonSpec) {
        spec = newSpec
        text = newSpec.label
        val sizePx = dp(newSpec.sizeDp)
        layoutParams = (layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(sizePx, sizePx)
        layoutParams.width = sizePx
        layoutParams.height = sizePx
        setTextSize(TypedValue.COMPLEX_UNIT_SP, when {
            newSpec.label.length >= 8 -> 9f
            newSpec.label.length >= 5 -> 11f
            newSpec.sizeDp >= 60 -> 15f
            newSpec.sizeDp >= 50 -> 13f
            else -> 12f
        })
        requestLayout()
    }

    fun layoutInParent(parentW: Int, parentH: Int) {
        if (parentW <= 0 || parentH <= 0) return
        val sizePx = dp(spec.sizeDp)
        val lp = (layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(sizePx, sizePx).also { layoutParams = it }
        lp.width = sizePx
        lp.height = sizePx
        lp.leftMargin = (spec.x * parentW - sizePx / 2f).toInt()
            .coerceIn(0, (parentW - sizePx).coerceAtLeast(0))
        lp.topMargin = (spec.y * parentH - sizePx / 2f).toInt()
            .coerceIn(0, (parentH - sizePx).coerceAtLeast(0))
        lp.gravity = Gravity.TOP or Gravity.START
        layoutParams = lp
    }

    fun releaseHold() {
        if (!holding) return
        holding = false
        when (spec.kind) {
            ControlButtonSpec.Kind.KEY_HOLD -> {
                val keys = spec.effectiveCodes()
                if (keys.size >= 2) GameInput.sendComboHold(keys, false)
                else GameInput.sendKeyEvent(spec.code, false)
            }
            ControlButtonSpec.Kind.MOUSE_HOLD -> GameInput.sendKeyEvent(mouseVirtualCode(spec.code), false)
            else -> Unit
        }
    }

    private fun refreshChrome() {
        setBackgroundResource(
            if (editSelected && editMode) R.drawable.bg_control_round_selected
            else R.drawable.bg_control_round
        )
        if (!isPressed) {
            alpha = if (editMode) 0.95f else 0.88f
            scaleX = 1f
            scaleY = 1f
        }
    }

    private fun setPlayPressed(pressed: Boolean) {
        isPressed = pressed
        if (pressed) {
            alpha = 1f
            scaleX = 0.90f
            scaleY = 0.90f
        } else {
            alpha = if (editMode) 0.95f else 0.88f
            scaleX = 1f
            scaleY = 1f
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return handleEdit(event)
        return handlePlay(event)
    }

    private fun handlePlay(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                setPlayPressed(true)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                when (spec.kind) {
                    ControlButtonSpec.Kind.KEY_HOLD -> {
                        val keys = spec.effectiveCodes()
                        if (keys.size >= 2) GameInput.sendComboHold(keys, true)
                        else GameInput.sendKeyEvent(spec.code, true)
                        holding = true
                    }
                    ControlButtonSpec.Kind.MOUSE_HOLD -> {
                        GameInput.sendKeyEvent(mouseVirtualCode(spec.code), true)
                        holding = true
                    }
                    ControlButtonSpec.Kind.KEY_TAP -> {
                        // fire on up to avoid accidental taps while scrolling UI
                    }
                    ControlButtonSpec.Kind.SCROLL,
                    ControlButtonSpec.Kind.SOFT_KEYBOARD -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                setPlayPressed(false)
                when (spec.kind) {
                    ControlButtonSpec.Kind.KEY_HOLD, ControlButtonSpec.Kind.MOUSE_HOLD -> releaseHold()
                    ControlButtonSpec.Kind.KEY_TAP -> {
                        val keys = spec.effectiveCodes()
                        if (keys.size >= 2) GameInput.sendComboTap(keys)
                        else GameInput.sendKeyTap(spec.code)
                    }
                    ControlButtonSpec.Kind.SCROLL -> {
                        val scroll = if (spec.code >= 0) GameInput.MOUSE_SCROLL_UP else GameInput.MOUSE_SCROLL_DOWN
                        GameInput.sendKeyEvent(scroll, true)
                    }
                    ControlButtonSpec.Kind.SOFT_KEYBOARD -> GameInput.toggleSoftKeyboard()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                setPlayPressed(false)
                releaseHold()
                return true
            }
        }
        return true
    }

    /** Map stored GLFW mouse button (0/1/2) to FCL-style virtual codes. */
    private fun mouseVirtualCode(glfwButton: Int): Int = when (glfwButton) {
        GlfwKeys.MOUSE_LEFT -> GameInput.MOUSE_LEFT
        GlfwKeys.MOUSE_RIGHT -> GameInput.MOUSE_RIGHT
        GlfwKeys.MOUSE_MIDDLE -> GameInput.MOUSE_MIDDLE
        else -> GameInput.MOUSE_LEFT
    }

    private fun handleEdit(event: MotionEvent): Boolean {
        val parent = parent as? ViewGroup ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onSelect?.invoke(this)
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
                if (!dragging && (abs(dx) > 8f || abs(dy) > 8f)) {
                    dragging = true
                }
                if (dragging && parent.width > 0 && parent.height > 0) {
                    val nx = (startX + dx / parent.width).coerceIn(0.05f, 0.95f)
                    val ny = (startY + dy / parent.height).coerceIn(0.05f, 0.95f)
                    spec = spec.copy(x = nx, y = ny)
                    layoutInParent(parent.width, parent.height)
                    onMoved?.invoke(this, nx, ny)
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

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).toInt()
}
