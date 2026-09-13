package com.booxin.launcher.ui.controller

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Focus feedback for controller navigation.
 * Never touches clipChildren / clipToPadding — that breaks NestedScrollView / RecyclerView.
 */
object ControllerFocusAnim {
    private const val TAG_LISTENER = 0x4346414E // "CFAN"
    private const val PRESS_SCALE = 0.96f
    private const val PRESS_MS = 90L
    private const val BOUNCE_MS = 180L

    private val overshoot = OvershootInterpolator(2.2f)
    private val decelerate = DecelerateInterpolator(1.4f)

    fun attach(view: View, elevate: Boolean = true) {
        if (view.getTag(TAG_LISTENER) == true) return
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        // Do not animate scale on focus for scrollable lists — only alpha hint.
        val existing = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            existing?.onFocusChange(v, hasFocus)
            if (elevate) {
                v.alpha = if (hasFocus) 1f else v.alpha.coerceAtLeast(0.92f)
            }
        }
        view.setTag(TAG_LISTENER, true)
    }

    /** Confirm squash only — safe for buttons, not applied continuously while scrolling. */
    fun playConfirm(view: View, then: (() -> Unit)? = null) {
        view.animate().cancel()
        view.animate()
            .scaleX(PRESS_SCALE)
            .scaleY(PRESS_SCALE)
            .setDuration(PRESS_MS)
            .setInterpolator(decelerate)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(BOUNCE_MS)
                    .setInterpolator(overshoot)
                    .withEndAction { then?.invoke() }
                    .start()
            }
            .start()
    }

    fun reset(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationZ = 0f
        view.elevation = 0f
    }

    fun tabPulse(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(100L)
            .setInterpolator(decelerate)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(160L)
                    .setInterpolator(overshoot)
                    .start()
            }
            .start()
    }
}
