package com.booxin.launcher.ui.launch.input

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.lwjgl.glfw.CallbackBridge

/**
 * Binds on-screen TextViews as hold / tap GLFW keys or mouse buttons.
 */
object ControlButtonBinder {

    fun bindHoldKey(view: View, key: Int) {
        view.setOnTouchListener(holdListener { pressed ->
            CallbackBridge.sendKey(key, pressed)
        })
    }

    fun bindTapKey(view: View, key: Int) {
        view.setOnClickListener {
            CallbackBridge.sendKeyTap(key)
        }
    }

    fun bindHoldMouse(view: View, button: Int) {
        view.setOnTouchListener(holdListener { pressed ->
            CallbackBridge.sendMouseButton(button, pressed)
        })
    }

    fun bindScroll(view: View, yOffset: Double) {
        view.setOnClickListener {
            CallbackBridge.sendScroll(0.0, yOffset)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun holdListener(onHold: (Boolean) -> Unit): View.OnTouchListener {
        return View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    onHold(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    onHold(false)
                    true
                }
                else -> true
            }
        }
    }

    fun styleChip(view: TextView) {
        view.setBackgroundResource(com.booxin.launcher.R.drawable.bg_control_chip)
        view.setTextColor(0xFFFFFFFF.toInt())
    }
}
