package com.booxin.launcher.ui.launch.input

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Full PC keyboard + combo-key picker for the control layout editor.
 */
object AddControlKeyDialog {

    data class Result(
        val label: String,
        val kind: ControlButtonSpec.Kind,
        val code: Int,
        val codes: List<Int> = emptyList(),
        val sizeDp: Int = 52
    )

    fun show(context: Context, onPicked: (Result) -> Unit) {
        val dm = context.resources.displayMetrics
        val density = dm.density
        fun dp(v: Int) = (v * density).toInt()
        val availW = dm.widthPixels
        val availH = dm.heightPixels
        val isLandscape = availW > availH
        // Title bar + negative button row (varies slightly by OEM).
        val dialogChromePx = dp(if (isLandscape) 92 else 108)
        val maxDialogH = (availH * if (isLandscape) 0.96f else 0.92f).toInt()
        val maxBodyH = (maxDialogH - dialogChromePx).coerceAtLeast(dp(200))
        // Compact layout on short screens (landscape phones, small panels).
        val compact = maxBodyH < dp(380) || (isLandscape && availH < dp(520))
        val keyBtnH = if (compact) 34 else 40
        val rootPadV = if (compact) 4 else 6
        val rootPadH = if (compact) 8 else 10

        var dialog: AlertDialog? = null
        var mode = 1 // 0 function, 1 keyboard, 2 combo
        var comboCtrl = false
        var comboShift = false
        var comboAlt = false
        var comboMain: Int? = null

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(rootPadH), dp(rootPadV), dp(rootPadH), dp(rootPadV))
        }
        val modeTabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val holdCheck = CheckBox(context).apply {
            text = "按住不放（松手即释放）"
            isChecked = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 13f)
        }
        val toggleCheck = CheckBox(context).apply {
            text = "点按锁定，再按松开"
            isChecked = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 13f)
        }
        val followCheck = CheckBox(context).apply {
            text = "跟手拖动（按住移动，松开回原位）"
            isChecked = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 13f)
        }
        val preview = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 12f else 13f)
            setPadding(0, dp(if (compact) 2 else 4), 0, dp(if (compact) 2 else 4))
        }
        val optionRow = LinearLayout(context).apply {
            orientation = if (compact && isLandscape) {
                LinearLayout.HORIZONTAL
            } else {
                LinearLayout.VERTICAL
            }
        }
        lateinit var contentHost: FrameMatch

        fun finish(result: Result) {
            dialog?.dismiss()
            onPicked(result)
        }

        fun kind(): ControlButtonSpec.Kind = when {
            followCheck.isChecked -> ControlButtonSpec.Kind.KEY_FOLLOW
            toggleCheck.isChecked -> ControlButtonSpec.Kind.KEY_TOGGLE
            holdCheck.isChecked -> ControlButtonSpec.Kind.KEY_HOLD
            else -> ControlButtonSpec.Kind.KEY_TAP
        }

        fun resolveFunctionKind(base: ControlButtonSpec.Kind): ControlButtonSpec.Kind {
            if (followCheck.isChecked) {
                return when (base) {
                    ControlButtonSpec.Kind.MOUSE_HOLD,
                    ControlButtonSpec.Kind.MOUSE_TOGGLE,
                    ControlButtonSpec.Kind.MOUSE_FOLLOW -> ControlButtonSpec.Kind.MOUSE_FOLLOW
                    else -> ControlButtonSpec.Kind.KEY_FOLLOW
                }
            }
            if (toggleCheck.isChecked) {
                return when (base) {
                    ControlButtonSpec.Kind.MOUSE_HOLD,
                    ControlButtonSpec.Kind.MOUSE_FOLLOW,
                    ControlButtonSpec.Kind.MOUSE_TOGGLE -> ControlButtonSpec.Kind.MOUSE_TOGGLE
                    else -> ControlButtonSpec.Kind.KEY_TOGGLE
                }
            }
            return base
        }

        fun refreshPreview() {
            val followHint = if (followCheck.isChecked) " · 跟手回弹" else ""
            preview.text = when (mode) {
                2 -> {
                    val mods = buildList {
                        if (comboCtrl) add(GlfwKeys.KEY_LEFT_CONTROL)
                        if (comboShift) add(GlfwKeys.KEY_LEFT_SHIFT)
                        if (comboAlt) add(GlfwKeys.KEY_LEFT_ALT)
                    }
                    val main = comboMain
                    when {
                        main == null -> "预览：先勾选 Ctrl/Shift/Alt，再点主键"
                        mods.isEmpty() -> "预览：请至少选择一个修饰键"
                        else -> "预览：" + GlfwKeys.comboLabel(mods + main) +
                            when {
                                followCheck.isChecked -> "（跟手按住）"
                                toggleCheck.isChecked -> "（点按锁定）"
                                holdCheck.isChecked -> "（按住）"
                                else -> "（点击）"
                            }
                    }
                }
                else -> "点下方键即可添加" + when {
                    followCheck.isChecked -> "（跟手按住，松开回位）"
                    toggleCheck.isChecked -> "（点按锁定）"
                    holdCheck.isChecked -> "（按住）"
                    else -> "（点击）"
                } + followHint.takeIf { mode == 0 }.orEmpty()
            }
        }

        fun keyBtn(label: String, weight: Float = 1f): Button =
            Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                minimumWidth = 0
                minimumHeight = 0
                setPadding(dp(2), dp(2), dp(2), dp(2))
                // Fixed width basis for horizontal rows (weight still works inside a measured row).
                val w = (dp(36) * weight).toInt().coerceAtLeast(dp(28))
                layoutParams = LinearLayout.LayoutParams(w, dp(keyBtnH)).apply {
                    marginStart = dp(1)
                    marginEnd = dp(1)
                    topMargin = dp(1)
                    bottomMargin = dp(1)
                }
            }

        fun rowOf(build: LinearLayout.() -> Unit): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                build()
            }

        /** One horizontal scroller per row — avoids nested HSV clipping vertical content. */
        fun scrollRow(build: LinearLayout.() -> Unit): HorizontalScrollView =
            HorizontalScrollView(context).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                isHorizontalScrollBarEnabled = false
                addView(
                    rowOf(build),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

        fun LinearLayout.addKey(label: String, code: Int, weight: Float = 1f, onClick: () -> Unit) {
            addView(keyBtn(label, weight).apply { setOnClickListener { onClick() } })
        }

        fun pickKey(code: Int, label: String = GlfwKeys.shortLabel(code)) {
            if (mode == 2) {
                comboMain = code
                refreshPreview()
                return
            }
            val size = when (code) {
                GlfwKeys.KEY_SPACE -> 64
                GlfwKeys.KEY_ENTER, GlfwKeys.KEY_BACKSPACE, GlfwKeys.KEY_TAB,
                GlfwKeys.KEY_LEFT_SHIFT, GlfwKeys.KEY_LEFT_CONTROL,
                GlfwKeys.KEY_LEFT_ALT -> 48
                else -> 44
            }
            finish(Result(label, kind(), code, emptyList(), size))
        }

        fun functionPanel(): View {
            val wrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(12))
            }
            data class Item(val label: String, val k: ControlButtonSpec.Kind, val code: Int, val size: Int)
            val items = listOf(
                Item("软键盘", ControlButtonSpec.Kind.SOFT_KEYBOARD, 0, 48),
                Item("左键", ControlButtonSpec.Kind.MOUSE_HOLD, GlfwKeys.MOUSE_LEFT, 56),
                Item("右键", ControlButtonSpec.Kind.MOUSE_HOLD, GlfwKeys.MOUSE_RIGHT, 56),
                Item("中键", ControlButtonSpec.Kind.MOUSE_HOLD, GlfwKeys.MOUSE_MIDDLE, 56),
                Item("滚轮▲", ControlButtonSpec.Kind.SCROLL, 1, 44),
                Item("滚轮▼", ControlButtonSpec.Kind.SCROLL, -1, 44),
                Item("跳", ControlButtonSpec.Kind.KEY_HOLD, GlfwKeys.KEY_SPACE, 64),
                Item("潜行", ControlButtonSpec.Kind.KEY_HOLD, GlfwKeys.KEY_LEFT_SHIFT, 52),
                Item("冲刺", ControlButtonSpec.Kind.KEY_HOLD, GlfwKeys.KEY_LEFT_CONTROL, 52),
                Item("攻击·跟手", ControlButtonSpec.Kind.MOUSE_FOLLOW, GlfwKeys.MOUSE_LEFT, 58),
                Item("使用·跟手", ControlButtonSpec.Kind.MOUSE_FOLLOW, GlfwKeys.MOUSE_RIGHT, 54),
                Item("跳·跟手", ControlButtonSpec.Kind.KEY_FOLLOW, GlfwKeys.KEY_SPACE, 66)
            )
            items.chunked(3).forEach { chunk ->
                wrap.addView(rowOf {
                    chunk.forEach { item ->
                        addView(keyBtn(item.label, 1f).apply {
                            layoutParams = LinearLayout.LayoutParams(0, dp(keyBtnH), 1f).apply {
                                marginStart = dp(1)
                                marginEnd = dp(1)
                                topMargin = dp(1)
                                bottomMargin = dp(1)
                            }
                            setOnClickListener {
                                val k = resolveFunctionKind(item.k)
                                val label = when {
                                    followCheck.isChecked && !item.label.contains("跟手") &&
                                        (item.k == ControlButtonSpec.Kind.KEY_HOLD ||
                                            item.k == ControlButtonSpec.Kind.MOUSE_HOLD) ->
                                        item.label + "·跟手"
                                    else -> item.label
                                }
                                finish(Result(label, k, item.code, emptyList(), item.size))
                            }
                        })
                    }
                })
            }
            return ScrollView(context).apply {
                isFillViewport = false
                clipToPadding = false
                addView(
                    wrap,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        fun keyboardPanel(): View {
            val wrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // Extra bottom pad so the last rows clear the dialog button bar visually.
                setPadding(0, 0, 0, dp(16))
            }

            wrap.addView(scrollRow {
                addKey("Esc", GlfwKeys.KEY_ESCAPE, 1.2f) { pickKey(GlfwKeys.KEY_ESCAPE) }
                listOf(
                    GlfwKeys.KEY_F1, GlfwKeys.KEY_F2, GlfwKeys.KEY_F3, GlfwKeys.KEY_F4,
                    GlfwKeys.KEY_F5, GlfwKeys.KEY_F6, GlfwKeys.KEY_F7, GlfwKeys.KEY_F8,
                    GlfwKeys.KEY_F9, GlfwKeys.KEY_F10, GlfwKeys.KEY_F11, GlfwKeys.KEY_F12
                ).forEach { c -> addKey(GlfwKeys.shortLabel(c), c) { pickKey(c) } }
            })
            wrap.addView(scrollRow {
                addKey("`", GlfwKeys.KEY_GRAVE_ACCENT) { pickKey(GlfwKeys.KEY_GRAVE_ACCENT) }
                listOf(
                    GlfwKeys.KEY_1, GlfwKeys.KEY_2, GlfwKeys.KEY_3, GlfwKeys.KEY_4, GlfwKeys.KEY_5,
                    GlfwKeys.KEY_6, GlfwKeys.KEY_7, GlfwKeys.KEY_8, GlfwKeys.KEY_9, GlfwKeys.KEY_0
                ).forEach { c -> addKey(GlfwKeys.shortLabel(c), c) { pickKey(c) } }
                addKey("-", GlfwKeys.KEY_MINUS) { pickKey(GlfwKeys.KEY_MINUS) }
                addKey("=", GlfwKeys.KEY_EQUAL) { pickKey(GlfwKeys.KEY_EQUAL) }
                addKey("Bksp", GlfwKeys.KEY_BACKSPACE, 1.5f) { pickKey(GlfwKeys.KEY_BACKSPACE) }
            })
            wrap.addView(scrollRow {
                addKey("Tab", GlfwKeys.KEY_TAB, 1.3f) { pickKey(GlfwKeys.KEY_TAB) }
                "QWERTYUIOP".forEach { ch ->
                    val c = GlfwKeys.KEY_A + (ch - 'A')
                    addKey(ch.toString(), c) { pickKey(c) }
                }
                addKey("[", GlfwKeys.KEY_LEFT_BRACKET) { pickKey(GlfwKeys.KEY_LEFT_BRACKET) }
                addKey("]", GlfwKeys.KEY_RIGHT_BRACKET) { pickKey(GlfwKeys.KEY_RIGHT_BRACKET) }
                addKey("\\", GlfwKeys.KEY_BACKSLASH, 1.2f) { pickKey(GlfwKeys.KEY_BACKSLASH) }
            })
            wrap.addView(scrollRow {
                addKey("Caps", GlfwKeys.KEY_CAPS_LOCK, 1.4f) { pickKey(GlfwKeys.KEY_CAPS_LOCK) }
                "ASDFGHJKL".forEach { ch ->
                    val c = GlfwKeys.KEY_A + (ch - 'A')
                    addKey(ch.toString(), c) { pickKey(c) }
                }
                addKey(";", GlfwKeys.KEY_SEMICOLON) { pickKey(GlfwKeys.KEY_SEMICOLON) }
                addKey("'", GlfwKeys.KEY_APOSTROPHE) { pickKey(GlfwKeys.KEY_APOSTROPHE) }
                addKey("Enter", GlfwKeys.KEY_ENTER, 1.5f) { pickKey(GlfwKeys.KEY_ENTER) }
            })
            wrap.addView(scrollRow {
                addKey("Shift", GlfwKeys.KEY_LEFT_SHIFT, 1.7f) { pickKey(GlfwKeys.KEY_LEFT_SHIFT) }
                "ZXCVBNM".forEach { ch ->
                    val c = GlfwKeys.KEY_A + (ch - 'A')
                    addKey(ch.toString(), c) { pickKey(c) }
                }
                addKey(",", GlfwKeys.KEY_COMMA) { pickKey(GlfwKeys.KEY_COMMA) }
                addKey(".", GlfwKeys.KEY_PERIOD) { pickKey(GlfwKeys.KEY_PERIOD) }
                addKey("/", GlfwKeys.KEY_SLASH) { pickKey(GlfwKeys.KEY_SLASH) }
                addKey("Shift", GlfwKeys.KEY_RIGHT_SHIFT, 1.7f) { pickKey(GlfwKeys.KEY_RIGHT_SHIFT) }
            })
            wrap.addView(scrollRow {
                addKey("Ctrl", GlfwKeys.KEY_LEFT_CONTROL, 1.2f) { pickKey(GlfwKeys.KEY_LEFT_CONTROL) }
                addKey("Win", GlfwKeys.KEY_LEFT_SUPER) { pickKey(GlfwKeys.KEY_LEFT_SUPER) }
                addKey("Alt", GlfwKeys.KEY_LEFT_ALT) { pickKey(GlfwKeys.KEY_LEFT_ALT) }
                addKey("空格", GlfwKeys.KEY_SPACE, 3.5f) { pickKey(GlfwKeys.KEY_SPACE, "空格") }
                addKey("Alt", GlfwKeys.KEY_RIGHT_ALT) { pickKey(GlfwKeys.KEY_RIGHT_ALT) }
                addKey("Menu", GlfwKeys.KEY_MENU) { pickKey(GlfwKeys.KEY_MENU) }
                addKey("Ctrl", GlfwKeys.KEY_RIGHT_CONTROL, 1.2f) { pickKey(GlfwKeys.KEY_RIGHT_CONTROL) }
            })
            wrap.addView(scrollRow {
                addKey("Ins", GlfwKeys.KEY_INSERT) { pickKey(GlfwKeys.KEY_INSERT) }
                addKey("Home", GlfwKeys.KEY_HOME) { pickKey(GlfwKeys.KEY_HOME) }
                addKey("PgUp", GlfwKeys.KEY_PAGE_UP) { pickKey(GlfwKeys.KEY_PAGE_UP) }
                addKey("Del", GlfwKeys.KEY_DELETE) { pickKey(GlfwKeys.KEY_DELETE) }
                addKey("End", GlfwKeys.KEY_END) { pickKey(GlfwKeys.KEY_END) }
                addKey("PgDn", GlfwKeys.KEY_PAGE_DOWN) { pickKey(GlfwKeys.KEY_PAGE_DOWN) }
                addKey("←", GlfwKeys.KEY_LEFT) { pickKey(GlfwKeys.KEY_LEFT) }
                addKey("↑", GlfwKeys.KEY_UP) { pickKey(GlfwKeys.KEY_UP) }
                addKey("↓", GlfwKeys.KEY_DOWN) { pickKey(GlfwKeys.KEY_DOWN) }
                addKey("→", GlfwKeys.KEY_RIGHT) { pickKey(GlfwKeys.KEY_RIGHT) }
            })
            wrap.addView(scrollRow {
                addKey("Num/", GlfwKeys.KEY_KP_DIVIDE) { pickKey(GlfwKeys.KEY_KP_DIVIDE) }
                addKey("Num*", GlfwKeys.KEY_KP_MULTIPLY) { pickKey(GlfwKeys.KEY_KP_MULTIPLY) }
                addKey("Num-", GlfwKeys.KEY_KP_SUBTRACT) { pickKey(GlfwKeys.KEY_KP_SUBTRACT) }
                addKey("Num+", GlfwKeys.KEY_KP_ADD) { pickKey(GlfwKeys.KEY_KP_ADD) }
                (0..9).forEach { n ->
                    addKey("N$n", GlfwKeys.KEY_KP_0 + n) { pickKey(GlfwKeys.KEY_KP_0 + n) }
                }
                addKey("Num.", GlfwKeys.KEY_KP_DECIMAL) { pickKey(GlfwKeys.KEY_KP_DECIMAL) }
                addKey("NumEnt", GlfwKeys.KEY_KP_ENTER, 1.3f) { pickKey(GlfwKeys.KEY_KP_ENTER) }
            })

            return ScrollView(context).apply {
                isFillViewport = false
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_ALWAYS
                addView(
                    wrap,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        fun comboPanel(): View {
            val wrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(16))
            }
            val mods = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            fun mod(title: String, get: () -> Boolean, set: (Boolean) -> Unit) {
                val b = keyBtn(title, 1f)
                b.layoutParams = LinearLayout.LayoutParams(0, dp(keyBtnH), 1f).apply {
                    marginStart = dp(1)
                    marginEnd = dp(1)
                }
                fun paint() {
                    b.alpha = if (get()) 1f else 0.5f
                }
                paint()
                b.setOnClickListener {
                    set(!get())
                    paint()
                    refreshPreview()
                }
                mods.addView(b)
            }
            mod("Ctrl", { comboCtrl }) { comboCtrl = it }
            mod("Shift", { comboShift }) { comboShift = it }
            mod("Alt", { comboAlt }) { comboAlt = it }
            wrap.addView(mods)
            wrap.addView(TextView(context).apply {
                text = "再点主键，然后点「添加组合键」"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(6), 0, dp(4))
            })
            wrap.addView(scrollRow {
                listOf(
                    GlfwKeys.KEY_1, GlfwKeys.KEY_2, GlfwKeys.KEY_3, GlfwKeys.KEY_4, GlfwKeys.KEY_5,
                    GlfwKeys.KEY_6, GlfwKeys.KEY_7, GlfwKeys.KEY_8, GlfwKeys.KEY_9, GlfwKeys.KEY_0
                ).forEach { c -> addKey(GlfwKeys.shortLabel(c), c) { pickKey(c) } }
            })
            wrap.addView(scrollRow {
                "QWERTYUIOP".forEach { ch ->
                    val c = GlfwKeys.KEY_A + (ch - 'A')
                    addKey(ch.toString(), c) { pickKey(c) }
                }
            })
            wrap.addView(scrollRow {
                "ASDFGHJKL".forEach { ch ->
                    val c = GlfwKeys.KEY_A + (ch - 'A')
                    addKey(ch.toString(), c) { pickKey(c) }
                }
            })
            wrap.addView(scrollRow {
                "ZXCVBNM".forEach { ch ->
                    val c = GlfwKeys.KEY_A + (ch - 'A')
                    addKey(ch.toString(), c) { pickKey(c) }
                }
                addKey("空格", GlfwKeys.KEY_SPACE, 2f) { pickKey(GlfwKeys.KEY_SPACE, "空格") }
                addKey("F3", GlfwKeys.KEY_F3) { pickKey(GlfwKeys.KEY_F3) }
                addKey("F5", GlfwKeys.KEY_F5) { pickKey(GlfwKeys.KEY_F5) }
                addKey("Tab", GlfwKeys.KEY_TAB) { pickKey(GlfwKeys.KEY_TAB) }
            })
            wrap.addView(Button(context).apply {
                text = "添加组合键"
                setOnClickListener {
                    val main = comboMain
                    if (main == null) {
                        preview.text = "预览：请先选择主键"
                        return@setOnClickListener
                    }
                    if (!comboCtrl && !comboShift && !comboAlt) {
                        preview.text = "预览：请至少选择一个修饰键"
                        return@setOnClickListener
                    }
                    val codes = buildList {
                        if (comboCtrl) add(GlfwKeys.KEY_LEFT_CONTROL)
                        if (comboShift) add(GlfwKeys.KEY_LEFT_SHIFT)
                        if (comboAlt) add(GlfwKeys.KEY_LEFT_ALT)
                        add(main)
                    }
                    finish(
                        Result(
                            label = GlfwKeys.comboLabel(codes),
                            kind = kind(),
                            code = main,
                            codes = codes,
                            sizeDp = 52
                        )
                    )
                }
            })
            return ScrollView(context).apply {
                isFillViewport = false
                clipToPadding = false
                addView(
                    wrap,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        fun showMode(m: Int) {
            mode = m
            contentHost.removeAllViews()
            contentHost.addView(
                when (m) {
                    0 -> functionPanel()
                    2 -> comboPanel()
                    else -> keyboardPanel()
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
            holdCheck.visibility = if (m == 0) View.GONE else View.VISIBLE
            toggleCheck.visibility = View.VISIBLE
            refreshPreview()
        }

        fun tab(title: String, index: Int): Button =
            Button(context).apply {
                text = title
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener { showMode(index) }
            }

        modeTabs.addView(tab("功能", 0))
        modeTabs.addView(tab("全键盘", 1))
        modeTabs.addView(tab("组合键", 2))
        holdCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) toggleCheck.isChecked = false
            if (!checked && followCheck.isChecked) followCheck.isChecked = false
            refreshPreview()
        }
        toggleCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                holdCheck.isChecked = false
                followCheck.isChecked = false
            }
            refreshPreview()
        }
        followCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                toggleCheck.isChecked = false
                holdCheck.isChecked = true
            }
            refreshPreview()
        }

        optionRow.addView(
            holdCheck,
            LinearLayout.LayoutParams(
                if (compact && isLandscape) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                if (compact && isLandscape) 1f else 0f
            )
        )
        optionRow.addView(
            toggleCheck,
            LinearLayout.LayoutParams(
                if (compact && isLandscape) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                if (compact && isLandscape) 1f else 0f
            )
        )
        optionRow.addView(
            followCheck,
            LinearLayout.LayoutParams(
                if (compact && isLandscape) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                if (compact && isLandscape) 1f else 0f
            )
        )
        root.addView(modeTabs)
        root.addView(optionRow)
        root.addView(preview)

        dialog = AlertDialog.Builder(context)
            .setTitle("添加按键")
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog?.show()
        dialog?.window?.let { win ->
            val dialogW = (availW * if (isLandscape) 0.98f else 0.96f).toInt()
                .coerceIn(dp(280), availW)
            win.setLayout(dialogW, maxDialogH)
        }
        // Measure fixed chrome, then give the keyboard area whatever vertical space remains.
        root.post {
            val measuredHeader = modeTabs.height + optionRow.height + preview.height +
                root.paddingTop + root.paddingBottom
            val headerH = measuredHeader.takeIf { it > 0 } ?: dp(if (compact) 120 else 150)
            val hostH = (maxBodyH - headerH).coerceIn(dp(120), dp(if (isLandscape) 400 else 520))
            contentHost = FrameMatch(context, hostH)
            root.addView(
                contentHost,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    hostH
                )
            )
            showMode(1)
        }
    }

    /** Fixed-height host so the dialog does not jump when switching tabs. */
    private class FrameMatch(context: Context, heightPx: Int) : LinearLayout(context) {
        init {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, heightPx)
            minimumHeight = heightPx
        }
    }
}
