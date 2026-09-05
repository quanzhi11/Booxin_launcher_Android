package com.booxin.launcher.ui.launch.input

import android.content.Context
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Routes each pointer to the hit child (button / joystick) or to [fallbackTarget]
 * (look pad) when the blank area is touched.
 *
 * Play-mode: if a finger starts on a normal button then slides outside it, the
 * pointer is cancelled on the button and handed to the look pad so camera look
 * resumes. Follow-style buttons keep the pointer while outside.
 */
class TouchPassthroughLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var fallbackTarget: View? = null

    private val targets = SparseArray<View>()
    private val locThis = IntArray(2)
    private val locTarget = IntArray(2)

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val fallback = fallbackTarget
        if (fallback == null || !fallback.isEnabled || !isEnabled) {
            return super.dispatchTouchEvent(ev)
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAll(ev)
                assignPointer(ev, ev.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                assignPointer(ev, ev.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                dispatchMoves(ev)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                releasePointer(ev, ev.actionIndex, cancel = false)
            }
            MotionEvent.ACTION_UP -> {
                releasePointer(ev, ev.actionIndex, cancel = false)
                if (targets.size() > 0) cancelAll(ev)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelAll(ev)
            }
        }
        return true
    }

    private fun hitChild(x: Float, y: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE || !child.isEnabled) continue
            if (isInsideChild(child, x, y)) return child
        }
        return null
    }

    /** Hit-test in parent coords; include translation so follow-dragged views stay hittable. */
    private fun isInsideChild(child: View, x: Float, y: Float): Boolean {
        val l = child.left + child.translationX
        val t = child.top + child.translationY
        return x >= l && x < l + child.width && y >= t && y < t + child.height
    }

    private fun assignPointer(ev: MotionEvent, index: Int) {
        val id = ev.getPointerId(index)
        val target = hitChild(ev.getX(index), ev.getY(index)) ?: fallbackTarget ?: return
        targets.put(id, target)
        dispatchToTarget(target, ev, index, MotionEvent.ACTION_DOWN)
    }

    private fun dispatchMoves(ev: MotionEvent) {
        val fallback = fallbackTarget
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            val target = targets.get(id) ?: continue
            val x = ev.getX(i)
            val y = ev.getY(i)
            // Slide off a normal button → hand off to look pad (resume mouse look).
            if (fallback != null &&
                target !== fallback &&
                target is ControlButtonView &&
                !target.retainsPointerWhileOutside() &&
                !isInsideChild(target, x, y)
            ) {
                dispatchToTarget(target, ev, i, MotionEvent.ACTION_CANCEL)
                targets.put(id, fallback)
                dispatchToTarget(fallback, ev, i, MotionEvent.ACTION_DOWN)
                dispatchToTarget(fallback, ev, i, MotionEvent.ACTION_MOVE)
                continue
            }
            dispatchToTarget(target, ev, i, MotionEvent.ACTION_MOVE)
        }
    }

    private fun releasePointer(ev: MotionEvent, index: Int, cancel: Boolean) {
        val id = ev.getPointerId(index)
        val target = targets.get(id) ?: return
        targets.remove(id)
        val action = if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
        dispatchToTarget(target, ev, index, action)
    }

    private fun cancelAll(ev: MotionEvent) {
        val size = targets.size()
        if (size == 0) return
        val ids = IntArray(size) { targets.keyAt(it) }
        for (id in ids) {
            val target = targets.get(id) ?: continue
            val index = ev.findPointerIndex(id).takeIf { it >= 0 } ?: 0
            dispatchToTarget(target, ev, index, MotionEvent.ACTION_CANCEL)
        }
        targets.clear()
    }

    private fun dispatchToTarget(target: View, src: MotionEvent, pointerIndex: Int, action: Int) {
        val xInThis = src.getX(pointerIndex)
        val yInThis = src.getY(pointerIndex)
        val localX: Float
        val localY: Float
        if (target.parent === this) {
            // Must subtract translation: follow modes move views via translationX/Y.
            // Ignoring it makes local coords grow every frame and the view "flies".
            localX = xInThis - target.left - target.translationX
            localY = yInThis - target.top - target.translationY
        } else {
            // getLocationOnScreen already includes translation.
            getLocationOnScreen(locThis)
            target.getLocationOnScreen(locTarget)
            localX = locThis[0] + xInThis - locTarget[0]
            localY = locThis[1] + yInThis - locTarget[1]
        }

        val obtained = MotionEvent.obtain(
            src.downTime,
            src.eventTime,
            action,
            localX,
            localY,
            src.metaState
        )
        // Keep touch source so GameTouchPad mouse-source filters stay correct.
        obtained.setSource(src.source)
        try {
            target.dispatchTouchEvent(obtained)
        } finally {
            obtained.recycle()
        }
    }
}
