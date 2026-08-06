package com.booxin.launcher.ui.launch.input

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * 虚拟按键 / 摇杆编辑。
 */
class ControlLayoutController(
    private val context: Context,
    private val host: FrameLayout,
    private val joystick: VirtualJoystick,
    private val floatingBall: TextView,
    private val editBar: View,
    private val onEditModeChanged: (Boolean) -> Unit = {}
) {
    private val buttons = mutableListOf<ControlButtonView>()
    private var selectedButton: ControlButtonView? = null
    private var joystickSelected = false
    private var joystickSpec = ControlLayoutData.JoystickSpec()
    private var floatingBallSpec = ControlLayoutData.FloatingBallSpec()

    var editMode: Boolean = false
        private set

    init {
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            relayoutAll()
        }
        joystick.onSelect = {
            clearButtonSelection()
            joystickSelected = true
            joystick.editSelected = true
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
        Toast.makeText(context, "拖动轮盘/按键改位置；点选后可缩放或删除", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "请先点选一个按键", Toast.LENGTH_SHORT).show()
            return
        }
        target.releaseHold()
        host.removeView(target)
        buttons.remove(target)
        selectedButton = null
        persist()
    }

    fun resizeSelected(deltaDp: Int) {
        if (!editMode) return
        if (joystickSelected) {
            val next = (joystickSpec.sizeDp + deltaDp).coerceIn(120, 220)
            joystickSpec = joystickSpec.copy(sizeDp = next)
            joystick.applySpec(joystickSpec)
            return
        }
        val target = selectedButton
        if (target == null) {
            Toast.makeText(context, "请先点选轮盘或按键", Toast.LENGTH_SHORT).show()
            return
        }
        val next = (target.spec.sizeDp + deltaDp).coerceIn(36, 96)
        target.applySpec(target.spec.copy(sizeDp = next))
        val parent = host
        if (parent.width > 0) target.layoutInParent(parent.width, parent.height)
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
                joystick.applySpec(joystickSpec)
                applyFloatingBall()
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

    fun releaseAllHolds() {
        buttons.forEach { it.releaseHold() }
        joystick.releaseKeys()
    }

    private fun loadAndInflate() {
        clearViews()
        val data = ControlLayoutStore.load(context)
        joystickSpec = data.joystick
        floatingBallSpec = data.floatingBall
        joystick.applySpec(joystickSpec)
        applyFloatingBall()
        data.buttons.forEach { attach(it, select = false) }
        host.post { relayoutAll() }
    }

    private fun applyFloatingBall() {
        val parent = floatingBall.parent as? FrameLayout ?: return
        parent.post {
            if (parent.width <= 0 || parent.height <= 0) return@post
            val size = floatingBall.width.coerceAtLeast(1)
            val lp = (floatingBall.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(size, size)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.width = floatingBall.layoutParams.width
            lp.height = floatingBall.layoutParams.height
            lp.leftMargin = (floatingBallSpec.x * parent.width - size / 2f).toInt()
                .coerceIn(0, parent.width - size)
            lp.topMargin = (floatingBallSpec.y * parent.height - size / 2f).toInt()
                .coerceIn(0, parent.height - size)
            floatingBall.layoutParams = lp
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
    }

    private fun persist() {
        ControlLayoutStore.save(
            context,
            ControlLayoutData(
                buttons = buttons.map { it.spec },
                joystick = joystickSpec,
                floatingBall = floatingBallSpec
            )
        )
    }
}
