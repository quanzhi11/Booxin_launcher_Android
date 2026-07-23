package com.booxin.launcher.ui.launch.input

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Full-screen button host that never consumes blank-area touches, so the
 * sibling [GameTouchPad] underneath can receive them (FCL layout pattern).
 */
class TouchPassthroughLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false
}
