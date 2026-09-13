package com.booxin.launcher.ui.controller

import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

/**
 * Focus helpers for directional navigation across launcher screens.
 */
object ControllerFocus {

    fun moveFocus(root: View, direction: Int): Boolean {
        val focused = root.findFocus()
        if (focused == null) {
            return focusFirst(root)
        }
        val group = root as? ViewGroup ?: return false
        val next = FocusFinder.getInstance().findNextFocus(group, focused, direction)
        if (next != null && next !== focused) {
            return next.requestFocus()
        }
        val rv = findAncestorRecycler(focused) ?: return false
        val child = findRvChild(focused, rv) ?: return false
        val pos = rv.getChildAdapterPosition(child)
        if (pos < 0) return false
        val count = rv.adapter?.itemCount ?: 0
        when (direction) {
            View.FOCUS_DOWN -> {
                if (pos >= count - 1) return false
                rv.smoothScrollToPosition(pos + 1)
                rv.post {
                    rv.findViewHolderForAdapterPosition(pos + 1)?.itemView?.requestFocus()
                }
                return true
            }
            View.FOCUS_UP -> {
                if (pos <= 0) return false
                rv.smoothScrollToPosition(pos - 1)
                rv.post {
                    rv.findViewHolderForAdapterPosition(pos - 1)?.itemView?.requestFocus()
                }
                return true
            }
        }
        return false
    }

    fun focusFirst(root: View): Boolean {
        val preferred = findPreferredFocus(root)
        if (preferred != null) return preferred.requestFocus()
        return root.requestFocus()
    }

    fun activateFocused(root: View): Boolean {
        val focused = root.findFocus() ?: return focusFirst(root)
        ControllerFocusAnim.playConfirm(focused) {
            focused.performClick()
        }
        return true
    }

    fun directionFor(action: ControllerInput.Action): Int? = when (action) {
        ControllerInput.Action.DPAD_UP -> View.FOCUS_UP
        ControllerInput.Action.DPAD_DOWN -> View.FOCUS_DOWN
        ControllerInput.Action.DPAD_LEFT -> View.FOCUS_LEFT
        ControllerInput.Action.DPAD_RIGHT -> View.FOCUS_RIGHT
        else -> null
    }

    private fun findPreferredFocus(root: View): View? {
        val launch = root.findViewWithTag<View>("controller_primary")
        if (launch != null && launch.isVisible && launch.isFocusable) return launch
        return findFirstFocusable(root)
    }

    private fun findFirstFocusable(view: View): View? {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findFirstFocusable(view.getChildAt(i))?.let { return it }
            }
        }
        if (view.isFocusable && view.isVisible && view.isEnabled) return view
        return null
    }

    private fun findAncestorRecycler(view: View): RecyclerView? {
        var p = view.parent
        while (p is View) {
            if (p is RecyclerView) return p
            p = p.parent
        }
        return null
    }

    private fun findRvChild(view: View, rv: RecyclerView): View? {
        var v: View? = view
        while (v != null && v.parent !== rv) {
            v = v.parent as? View
        }
        return v
    }
}
