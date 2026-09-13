package com.booxin.launcher.ui.controller

import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.google.android.material.card.MaterialCardView

/**
 * Focusable targets for controller / keyboard. No clipChildren changes, no scale-on-scroll.
 */
object ControllerNavBinder {

    private const val TAG_BOUND = 0x434E4156 // "CNAV"

    fun bindItem(view: View) {
        if (view.getTag(TAG_BOUND) == true) return
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.isClickable = true
        ensureFocusHighlight(view)
        view.setTag(TAG_BOUND, true)
    }

    fun bindButton(view: View, primary: Boolean = false) {
        if (primary) view.tag = "controller_primary"
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        ensureFocusHighlight(view)
        ControllerFocusAnim.attach(view, elevate = false)
    }

    fun bindRecycler(recycler: RecyclerView) {
        // Keep default clipping so edge scroll / overscroll stays correct.
        recycler.isFocusable = false
        recycler.addOnChildAttachStateChangeListener(object :
            RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                bindItem(view)
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                ControllerFocusAnim.reset(view)
            }
        })
        for (i in 0 until recycler.childCount) {
            bindItem(recycler.getChildAt(i))
        }
    }

    fun bindTopNavButtons(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.isFocusable = true
            child.isFocusableInTouchMode = false
        }
    }

    private fun ensureFocusHighlight(view: View) {
        if (view is MaterialCardView) {
            val strokeIdle = view.strokeColor
            val strokeW = view.strokeWidth.coerceAtLeast(1)
            val existing = view.onFocusChangeListener
            view.setOnFocusChangeListener { v, hasFocus ->
                existing?.onFocusChange(v, hasFocus)
                val card = v as MaterialCardView
                if (hasFocus) {
                    card.strokeWidth =
                        (strokeW + v.resources.displayMetrics.density).toInt()
                    card.strokeColor =
                        ContextCompat.getColor(v.context, R.color.booxin_cyan)
                } else {
                    card.strokeWidth = strokeW
                    card.strokeColor = strokeIdle
                }
            }
            return
        }
        if (view.foreground == null) {
            view.foreground =
                ContextCompat.getDrawable(view.context, R.drawable.bg_controller_focus_fg)
        }
    }
}
