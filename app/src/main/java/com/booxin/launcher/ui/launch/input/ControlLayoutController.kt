package com.booxin.launcher.ui.launch.input

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.booxin.launcher.R
import com.booxin.launcher.core.uiplugin.UiPluginManager

/**
 * 虚拟按键 / 摇杆编辑。
 */
class ControlLayoutController(
    private val context: Context,
    private val host: FrameLayout,
    private val joystick: VirtualJoystick,
    private val floatingBall: TextView,
    private val gestureQuick: TextView,
    private val editBar: View,
    private val followToggleView: TextView? = null,
    private val onEditModeChanged: (Boolean) -> Unit = {}
) {
    private val buttons = mutableListOf<ControlButtonView>()
    private var selectedButton: ControlButtonView? = null
    private var joystickSelected = false
    private var joystickSpec = ControlLayoutData.JoystickSpec()
    private var floatingBallSpec = ControlLayoutData.FloatingBallSpec()
    private var gestureQuickSpec = ControlLayoutData.GestureQuickSpec()
    private var buttonStyle: ControlButtonStyle? = null

    var editMode: Boolean = false
        private set

    init {
        host.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            // Only when host size changes — re-applying layoutParams every frame
            // caused an infinite requestLayout storm (ColorOS jank / 卡顿).
            val w = right - left
            val h = bottom - top
            val ow = oldRight - oldLeft
            val oh = oldBottom - oldTop
            if (w != ow || h != oh) {
                relayoutAll()
            }
        }
        joystick.onSelect = {
            clearButtonSelection()
            joystickSelected = true
            joystick.editSelected = true
            refreshFollowToggleLabel()
        }
        joystick.onMoved = { spec ->
            joystickSpec = spec
        }
        loadAndInflate()
    }

    fun setButtonsVisible(visible: Boolean) {
        if (editMode && !visible) return
        host.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) releaseAllHolds()
    }

    fun enterEditMode() {
        if (editMode) return
        editMode = true
        clearSelection()
        buttons.forEach {
            it.editMode = true
            it.releaseHold()
        }
        joystick.editMode = true
        joystick.visibility = View.VISIBLE
        editBar.visibility = View.VISIBLE
        host.visibility = View.VISIBLE
        onEditModeChanged(true)
        Toast.makeText(context, "拖动改位置；点选轮盘/按键后可跟手、缩放或删除", Toast.LENGTH_SHORT).show()
        refreshFollowToggleLabel()
    }

    fun exitEditMode(save: Boolean) {
        if (!editMode) return
        if (save) persist()
        editMode = false
        clearSelection()
        buttons.forEach { it.editMode = false }
        joystick.editMode = false
        editBar.visibility = View.GONE
        onEditModeChanged(false)
    }

    fun addButton() {
        if (!editMode) enterEditMode()
        AddControlKeyDialog.show(context) { picked ->
            val spec = ControlButtonSpec(
                id = ControlLayoutStore.newId(),
                label = picked.label,
                kind = picked.kind,
                code = picked.code,
                x = 0.85f,
                y = 0.45f,
                sizeDp = picked.sizeDp,
                codes = picked.codes
            )
            attach(spec, select = true)
            persist()
        }
    }

    fun deleteSelected() {
        if (joystickSelected) {
            Toast.makeText(context, "移动轮盘不能删除，只能拖动或缩放", Toast.LENGTH_SHORT).show()
            return
        }
        val target = selectedButton
        if (target == null) {
            Toast.makeText(context, context.getString(R.string.control_edit_need_select), Toast.LENGTH_SHORT).show()
            return
        }
        target.releaseHold()
        host.removeView(target)
        buttons.remove(target)
        selectedButton = null
        refreshFollowToggleLabel()
        persist()
    }

    /** Toggle follow on the selected button, or on the movement joystick. */
    fun toggleFollowSelected() {
        if (!editMode) return
        if (joystickSelected) {
            val enable = !joystickSpec.follow
            joystickSpec = joystickSpec.copy(follow = enable)
            joystick.applySpec(joystickSpec)
            refreshFollowToggleLabel()
            persist()
            Toast.makeText(
                context,
                context.getString(
                    if (enable) R.string.control_edit_follow_enabled
                    else R.string.control_edit_follow_disabled
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val target = selectedButton
        if (target == null) {
            Toast.makeText(context, context.getString(R.string.control_edit_need_select), Toast.LENGTH_SHORT).show()
            return
        }
        if (!target.spec.canToggleFollow()) {
            Toast.makeText(
                context,
                context.getString(R.string.control_edit_follow_unsupported),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val enable = !target.spec.isFollowKind()
        target.applySpec(target.spec.withFollow(enable))
        if (host.width > 0) target.layoutInParent(host.width, host.height)
        refreshFollowToggleLabel()
        persist()
        Toast.makeText(
            context,
            context.getString(
                if (enable) R.string.control_edit_follow_enabled
                else R.string.control_edit_follow_disabled
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun resizeSelected(deltaDp: Int) {
        if (!editMode) return
        if (joystickSelected) {
            val next = (joystickSpec.sizeDp + deltaDp).coerceIn(120, 220)
            joystickSpec = joystickSpec.copy(sizeDp = next)
            joystick.applySpec(joystickSpec)
            persist()
            return
        }
        val target = selectedButton
        if (target == null) {
            Toast.makeText(context, "请先点选轮盘或按键", Toast.LENGTH_SHORT).show()
            return
        }
        val next = (target.spec.sizeDp + deltaDp).coerceIn(
            ControlLayoutStore.SIZE_DP_MIN,
            ControlLayoutStore.SIZE_DP_MAX
        )
        target.applySpec(target.spec.copy(sizeDp = next))
        val parent = host
        if (parent.width > 0) target.layoutInParent(parent.width, parent.height)
        persist()
    }

    fun resetToDefault() {
        AlertDialog.Builder(context)
            .setTitle("恢复默认布局")
            .setMessage("将清除当前自定义按键并恢复默认位置，确定？")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                clearViews()
                val def = ControlLayoutStore.default()
                joystickSpec = def.joystick
                floatingBallSpec = def.floatingBall
                gestureQuickSpec = def.gestureQuick
                joystick.applySpec(joystickSpec)
                applyFloatingBall()
                applyGestureQuick()
                def.buttons.forEach { attach(it, select = false) }
                persist()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun updateFloatingBallFromView() {
        val parent = floatingBall.parent as? FrameLayout ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        val cx = (floatingBall.left + floatingBall.width / 2f) / parent.width
        val cy = (floatingBall.top + floatingBall.height / 2f) / parent.height
        floatingBallSpec = ControlLayoutData.FloatingBallSpec(
            x = cx.coerceIn(0.05f, 0.95f),
            y = cy.coerceIn(0.05f, 0.95f)
        )
        persist()
    }

    fun updateGestureQuickFromView() {
        val parent = gestureQuick.parent as? FrameLayout ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        val cx = (gestureQuick.left + gestureQuick.width / 2f) / parent.width
        val cy = (gestureQuick.top + gestureQuick.height / 2f) / parent.height
        gestureQuickSpec = ControlLayoutData.GestureQuickSpec(
            x = cx.coerceIn(0.05f, 0.95f),
            y = cy.coerceIn(0.05f, 0.95f)
        )
        persist()
    }

    fun releaseAllHolds() {
        buttons.forEach { it.releaseHold() }
        joystick.releaseKeys()
    }

    /** Active panel id (`main` or an extraLayouts id). */
    fun currentLayoutId(): String = ControlLayoutStore.activeLayoutId(context)

    /** Main + plugin extraLayouts (may be only main when no plugin). */
    fun availableLayouts() = ControlLayoutStore.listControlLayouts()

    /**
     * Switch FCL-style control panel. Saves current edits first when in edit mode.
     * @return true if switched (or already on that panel).
     */
    fun switchLayout(layoutId: String): Boolean {
        val layouts = availableLayouts()
        if (layouts.isEmpty()) return false
        val target = layouts.firstOrNull { it.id.equals(layoutId.trim(), ignoreCase = true) }
            ?: return false
        val current = currentLayoutId()
        if (target.id.equals(current, ignoreCase = true)) return true
        persist()
        val keepChrome = !target.id.equals(UiPluginManager.MAIN_LAYOUT_ID, ignoreCase = true)
        val prevJoy = joystickSpec
        val prevBall = floatingBallSpec
        val prevGesture = gestureQuickSpec
        ControlLayoutStore.setActiveLayoutId(context, target.id)
        loadAndInflate(
            preserveChrome = keepChrome,
            chromeJoystick = prevJoy,
            chromeBall = prevBall,
            chromeGesture = prevGesture
        )
        if (editMode) {
            buttons.forEach { it.editMode = true }
            joystick.editMode = true
        }
        return true
    }

    private fun loadAndInflate(
        preserveChrome: Boolean = false,
        chromeJoystick: ControlLayoutData.JoystickSpec? = null,
        chromeBall: ControlLayoutData.FloatingBallSpec? = null,
        chromeGesture: ControlLayoutData.GestureQuickSpec? = null
    ) {
        clearViews()
        val data = ControlLayoutStore.load(context)
        joystickSpec = if (preserveChrome && chromeJoystick != null) chromeJoystick else data.joystick
        floatingBallSpec =
            if (preserveChrome && chromeBall != null) chromeBall else data.floatingBall
        gestureQuickSpec =
            if (preserveChrome && chromeGesture != null) chromeGesture else data.gestureQuick
        buttonStyle = data.buttonStyle
        joystick.applySpec(joystickSpec)
        applyFloatingBall()
        applyGestureQuick()
        data.buttons.forEach { attach(it, select = false) }
        host.post { relayoutAll() }
    }

    private fun applyFloatingBall() {
        applyHudBall(floatingBall, floatingBallSpec.x, floatingBallSpec.y)
    }

    private fun applyGestureQuick() {
        applyHudBall(gestureQuick, gestureQuickSpec.x, gestureQuickSpec.y)
    }

    private fun applyHudBall(view: TextView, nx: Float, ny: Float) {
        val parent = view.parent as? FrameLayout ?: return
        parent.post {
            if (parent.width <= 0 || parent.height <= 0) return@post
            val size = view.width.coerceAtLeast(1)
            val left = (nx * parent.width - size / 2f).toInt()
                .coerceIn(0, parent.width - size)
            val top = (ny * parent.height - size / 2f).toInt()
                .coerceIn(0, parent.height - size)
            val existing = view.layoutParams as? FrameLayout.LayoutParams
            if (existing != null &&
                existing.leftMargin == left &&
                existing.topMargin == top &&
                existing.width == view.layoutParams.width &&
                existing.height == view.layoutParams.height
            ) {
                return@post
            }
            val lp = existing ?: FrameLayout.LayoutParams(size, size)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.width = view.layoutParams.width
            lp.height = view.layoutParams.height
            lp.leftMargin = left
            lp.topMargin = top
            view.layoutParams = lp
        }
    }

    private fun attach(spec: ControlButtonSpec, select: Boolean) {
        val view = ControlButtonView(context, spec)
        view.onSelect = { v ->
            joystickSelected = false
            joystick.editSelected = false
            selectedButton?.editSelected = false
            selectedButton = v
            v.editSelected = true
            refreshFollowToggleLabel()
        }
        view.onMoved = { v, x, y ->
            v.spec = v.spec.copy(x = x, y = y)
        }
        view.editMode = editMode
        host.addView(view)
        buttons.add(view)
        host.post {
            view.layoutInParent(host.width, host.height)
            if (select) {
                joystickSelected = false
                joystick.editSelected = false
                selectedButton?.editSelected = false
                selectedButton = view
                view.editSelected = true
                refreshFollowToggleLabel()
            }
        }
    }

    private fun clearViews() {
        buttons.forEach {
            it.releaseHold()
            host.removeView(it)
        }
        buttons.clear()
        clearSelection()
    }

    private fun clearButtonSelection() {
        selectedButton?.editSelected = false
        selectedButton = null
    }

    private fun clearSelection() {
        clearButtonSelection()
        joystickSelected = false
        joystick.editSelected = false
        refreshFollowToggleLabel()
    }

    private fun refreshFollowToggleLabel() {
        val chip = followToggleView ?: return
        val followOn = when {
            joystickSelected -> joystickSpec.follow
            else -> selectedButton?.spec?.isFollowKind() == true
        }
        chip.text = context.getString(
            if (followOn) R.string.control_edit_follow_on else R.string.control_edit_follow
        )
        chip.alpha = when {
            joystickSelected -> 1f
            selectedButton == null -> 0.45f
            selectedButton!!.spec.canToggleFollow() -> 1f
            else -> 0.45f
        }
    }

    private fun relayoutAll() {
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        buttons.forEach { it.layoutInParent(w, h) }
        val parent = joystick.parent as? FrameLayout
        if (parent != null && parent.width > 0) {
            joystick.layoutInParent(parent.width, parent.height)
        }
        applyFloatingBall()
        applyGestureQuick()
    }

    private fun persist() {
        ControlLayoutStore.save(
            context,
            ControlLayoutData(
                buttons = buttons.map { it.spec },
                joystick = joystickSpec,
                floatingBall = floatingBallSpec,
                gestureQuick = gestureQuickSpec,
                buttonStyle = buttonStyle
            )
        )
    }
}
