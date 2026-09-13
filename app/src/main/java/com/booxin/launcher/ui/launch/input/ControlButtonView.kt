package com.booxin.launcher.ui.launch.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.booxin.launcher.R
import com.booxin.launcher.core.uiplugin.UiPluginManager
import kotlin.math.abs

/**
 * A single virtual control: play-mode injects GLFW; edit-mode can be dragged / selected.
 *
 * Chrome priority: [ControlButtonSpec.icon] image → CSS-like [ControlButtonSpec.style] → default round.
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
    private var iconDrawable: Drawable? = null
    private var styleDrawable: Drawable? = null
    /** Play-mode follow: finger anchor in view-local coords (layout box, not translated). */
    private var followDownLocalX = 0f
    private var followDownLocalY = 0f

    init {
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        applySpec(spec)
        refreshChrome()
    }

    /** Follow buttons keep the pointer when the finger leaves the original hit box. */
    fun retainsPointerWhileOutside(): Boolean = !editMode && spec.isFollowKind()

    fun applySpec(newSpec: ControlButtonSpec) {
        spec = newSpec
        text = newSpec.label
        iconDrawable = loadIcon(newSpec.icon)
        styleDrawable = newSpec.style
            ?.takeUnless { it.isEmpty() }
            ?.let { ControlButtonStyle.toDrawable(it, resources.displayMetrics.density) }
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
        setTextColor(newSpec.style?.textColor ?: 0xFFFFFFFF.toInt())
        // Transparent stock chrome needs a soft shadow so labels stay readable over the game.
        val stockTransparent = !hasVisualChrome(newSpec)
        val needsShadow = stockTransparent ||
            iconDrawable != null ||
            (styleDrawable != null && isLightFill(newSpec.style))
        if (needsShadow) {
            setShadowLayer(3.5f, 0f, 1.2f, 0xCC000000.toInt())
        } else {
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        // Reset follow offset when layout changes.
        animate().cancel()
        translationX = 0f
        translationY = 0f
        refreshChrome()
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
    }

    fun releaseHold() {
        if (!holding) return
        holding = false
        when (spec.kind) {
            ControlButtonSpec.Kind.KEY_HOLD,
            ControlButtonSpec.Kind.KEY_FOLLOW,
            ControlButtonSpec.Kind.KEY_TOGGLE -> {
                val keys = spec.effectiveCodes()
                if (keys.size >= 2) GameInput.sendComboHold(keys, false)
                else GameInput.sendKeyEvent(spec.code, false)
            }
            ControlButtonSpec.Kind.MOUSE_HOLD,
            ControlButtonSpec.Kind.MOUSE_FOLLOW,
            ControlButtonSpec.Kind.MOUSE_TOGGLE ->
                GameInput.sendKeyEvent(mouseVirtualCode(spec.code), false)
            else -> Unit
        }
    }

    private fun snapFollowHome() {
        if (!spec.isFollowKind()) {
            translationX = 0f
            translationY = 0f
            return
        }
        animate().cancel()
        animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun pressHoldKey() {
        when (spec.kind) {
            ControlButtonSpec.Kind.KEY_HOLD,
            ControlButtonSpec.Kind.KEY_FOLLOW,
            ControlButtonSpec.Kind.KEY_TOGGLE -> {
                val keys = spec.effectiveCodes()
                if (keys.size >= 2) GameInput.sendComboHold(keys, true)
                else GameInput.sendKeyEvent(spec.code, true)
                holding = true
            }
            ControlButtonSpec.Kind.MOUSE_HOLD,
            ControlButtonSpec.Kind.MOUSE_FOLLOW,
            ControlButtonSpec.Kind.MOUSE_TOGGLE -> {
                GameInput.sendKeyEvent(mouseVirtualCode(spec.code), true)
                holding = true
            }
            else -> Unit
        }
    }

    private fun loadIcon(relative: String): Drawable? {
        val file = UiPluginManager.resolveControlLayoutAsset(relative) ?: return null
        val bmp = runCatching {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            })
        }.getOrNull() ?: return null
        return BitmapDrawable(resources, bmp).also {
            it.isFilterBitmap = true
        }
    }

    private fun stockHollowDrawable(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            // Match「白描边」default: clear fill + brighter white ring.
            val stroke = if (selected) 0xFF4FC3F7.toInt() else 0xCCFFFFFF.toInt()
            val width = if (selected) dp(3) else dp(2)
            setStroke(width.coerceAtLeast(1), stroke)
        }
    }

    private fun isLightFill(style: ControlButtonStyle?): Boolean {
        if (style == null) return false
        val sample = style.gradientColors.firstOrNull() ?: style.backgroundColor ?: return false
        val r = Color.red(sample)
        val g = Color.green(sample)
        val b = Color.blue(sample)
        return (r + g + b) / 3f > 190f
    }

    private fun refreshChrome() {
        val base: Drawable? = when {
            iconDrawable != null ->
                iconDrawable!!.constantState?.newDrawable()?.mutate() ?: iconDrawable!!.mutate()
            styleDrawable != null ->
                styleDrawable!!.constantState?.newDrawable()?.mutate() ?: styleDrawable!!.mutate()
            else -> null
        }
        if (base != null) {
            background = if (editSelected && editMode) {
                val ring = GradientDrawable().apply {
                    val shape = spec.style?.resolvedShape()
                    this.shape = when (shape) {
                        ControlButtonStyle.Shape.RECT,
                        ControlButtonStyle.Shape.ROUND_RECT -> GradientDrawable.RECTANGLE
                        else -> GradientDrawable.OVAL
                    }
                    if (shape == ControlButtonStyle.Shape.ROUND_RECT) {
                        cornerRadius =
                            (spec.style?.borderRadiusDp ?: 16f) * resources.displayMetrics.density
                    }
                    setStroke(dp(3), 0xFF4FC3F7.toInt())
                    setColor(Color.TRANSPARENT)
                }
                LayerDrawable(arrayOf(base, ring))
            } else {
                base
            }
        } else {
            // Hollow ring in code — avoids opaque pressed-state drawables.
            background = stockHollowDrawable(editSelected && editMode)
        }
        val styleOpacity = spec.style?.opacity
        if (!isPressed && !holding) {
            animate().cancel()
            alpha = styleOpacity ?: idleChromeAlpha()
            scaleX = 1f
            scaleY = 1f
            // Don't clear follow offset here — snapFollowHome owns that animation.
            if (!spec.isFollowKind() || (translationX == 0f && translationY == 0f)) {
                translationY = 0f
            }
        }
    }

    /** True when icon/plugin/user color style paints a visible chrome (not hollow stock). */
    private fun hasVisualChrome(s: ControlButtonSpec = spec): Boolean {
        if (s.icon.isNotBlank()) return true
        val st = s.style ?: return false
        if (st.isEmpty()) return false
        // Explicit transparent fill + no gradient still counts as "styled" if colors set,
        // but stock hollow path is only when style is null/empty.
        return true
    }

    private fun hasPluginChrome(): Boolean = hasVisualChrome()

    /**
     * Stock hollow buttons keep view-alpha high (bg is already clear).
     * Styled / plugin chrome use the previous higher opacity defaults.
     */
    private fun idleChromeAlpha(): Float = when {
        hasPluginChrome() -> if (editMode) 0.95f else 0.92f
        editMode -> 0.92f
        else -> 0.88f
    }

    private fun setPlayPressed(pressed: Boolean) {
        isPressed = pressed
        val style = spec.style
        val styleOpacity = style?.opacity
        val targetScale = (style?.pressScale ?: 0.90f).coerceIn(0.72f, 1f)
        val jelly = style?.pressEffect == ControlButtonStyle.PressEffect.JELLY && !spec.isFollowKind()
        animate().cancel()
        if (pressed) {
            alpha = if (hasPluginChrome()) 1f else 1f
            if (jelly) {
                // Squash wider + shorter, slight drop — jelly compress.
                animate()
                    .scaleX(targetScale * 1.08f)
                    .scaleY(targetScale * 0.88f)
                    .translationY(dp(2).toFloat())
                    .setDuration(90L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                scaleX = targetScale
                scaleY = targetScale
                translationY = 0f
            }
        } else {
            val idleAlpha = styleOpacity ?: idleChromeAlpha()
            if (jelly) {
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .alpha(idleAlpha)
                    .setDuration(280L)
                    .setInterpolator(OvershootInterpolator(2.6f))
                    .withEndAction { refreshChrome() }
                    .start()
            } else {
                alpha = idleAlpha
                scaleX = 1f
                scaleY = 1f
                translationY = 0f
                refreshChrome()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return handleEdit(event)
        return handlePlay(event)
    }

    private fun isToggleHoldKind(): Boolean = spec.isToggleHoldKind()

    private fun applyLatchVisual(latched: Boolean) {
        isPressed = latched
        animate().cancel()
        if (latched) {
            val targetScale = (spec.style?.pressScale ?: 0.90f).coerceIn(0.72f, 1f)
            alpha = if (hasPluginChrome()) 1f else 0.88f
            scaleX = targetScale
            scaleY = targetScale
            translationY = 0f
        } else {
            refreshChrome()
        }
    }

    private fun handlePlay(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                setPlayPressed(true)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (spec.isFollowKind()) {
                    animate().cancel()
                    translationX = 0f
                    translationY = 0f
                    followDownLocalX = event.x
                    followDownLocalY = event.y
                    pressHoldKey()
                    return true
                }
                if (isToggleHoldKind()) {
                    return true
                }
                when (spec.kind) {
                    ControlButtonSpec.Kind.KEY_HOLD,
                    ControlButtonSpec.Kind.MOUSE_HOLD -> pressHoldKey()
                    ControlButtonSpec.Kind.KEY_TAP,
                    ControlButtonSpec.Kind.SCROLL,
                    ControlButtonSpec.Kind.SOFT_KEYBOARD -> Unit
                    else -> Unit
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (spec.isFollowKind()) {
                    translationX += event.x - followDownLocalX
                    translationY += event.y - followDownLocalY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (spec.isFollowKind()) {
                    setPlayPressed(false)
                    releaseHold()
                    snapFollowHome()
                    return true
                }
                if (isToggleHoldKind()) {
                    if (holding) {
                        releaseHold()
                        applyLatchVisual(false)
                    } else {
                        pressHoldKey()
                        applyLatchVisual(true)
                    }
                    return true
                }
                setPlayPressed(false)
                when (spec.kind) {
                    ControlButtonSpec.Kind.KEY_HOLD,
                    ControlButtonSpec.Kind.MOUSE_HOLD -> releaseHold()
                    ControlButtonSpec.Kind.KEY_TAP -> {
                        val text = spec.outputText.trim()
                        if (text.isNotEmpty()) {
                            GameInput.sendChatText(text)
                        } else {
                            val keys = spec.effectiveCodes()
                            if (keys.size >= 2) GameInput.sendComboTap(keys)
                            else if (spec.code != 0) GameInput.sendKeyTap(spec.code)
                        }
                    }
                    ControlButtonSpec.Kind.SCROLL -> {
                        val scroll =
                            if (spec.code >= 0) GameInput.MOUSE_SCROLL_UP else GameInput.MOUSE_SCROLL_DOWN
                        GameInput.sendKeyEvent(scroll, true)
                    }
                    ControlButtonSpec.Kind.SOFT_KEYBOARD -> GameInput.toggleSoftKeyboard()
                    else -> Unit
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (spec.isFollowKind()) {
                    setPlayPressed(false)
                    releaseHold()
                    snapFollowHome()
                    return true
                }
                if (isToggleHoldKind()) {
                    applyLatchVisual(holding)
                    return true
                }
                setPlayPressed(false)
                if (spec.kind == ControlButtonSpec.Kind.KEY_HOLD ||
                    spec.kind == ControlButtonSpec.Kind.MOUSE_HOLD
                ) {
                    releaseHold()
                }
                return true
            }
        }
        return true
    }

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
