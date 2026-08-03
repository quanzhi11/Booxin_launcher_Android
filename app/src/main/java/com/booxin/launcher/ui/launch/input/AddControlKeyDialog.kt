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
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var dialog: AlertDialog? = null
        var mode = 1 // 0 function, 1 keyboard, 2 combo
        var comboCtrl = false
        var comboShift = false
        var comboAlt = false
        var comboMain: Int? = null

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val modeTabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val holdCheck = CheckBox(context).apply {
            text = "按住不放（否则为点击）"
            isChecked = false
        }
        val preview = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(4))
        }
        val contentHost = FrameMatch(context, dp(300))

        fun finish(result: Result) {
            dialog?.dismiss()
            onPicked(result)
        }

        fun kind(): ControlButtonSpec.Kind =
            if (holdCheck.isChecked) ControlButtonSpec.Kind.KEY_HOLD else ControlButtonSpec.Kind.KEY_TAP

        fun refreshPreview() {
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
                            if (holdCheck.isChecked) "（按住）" else "（点击）"
                    }
                }
                else -> "点下方键即可添加" + if (holdCheck.isChecked) "（按住）" else "（点击）"
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
                layoutParams = LinearLayout.LayoutParams(0, dp(38), weight).apply {
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
            val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
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
                Item("冲刺", ControlButtonSpec.Kind.KEY_HOLD, GlfwKeys.KEY_LEFT_CONTROL, 52)
            )
            items.chunked(3).forEach { chunk ->
                wrap.addView(rowOf {
                    chunk.forEach { item ->
                        addView(keyBtn(item.label, 1f).apply {
                            setOnClickListener {
                                finish(Result(item.label, item.k, item.code, emptyList(), item.size))
                            }
                        })
                    }
                })
            }
            return ScrollView(context).apply { addView(wrap) }
        }

        fun keyboardPanel(): View {
            val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            wrap.addView(rowOf {
                addKey("Esc", GlfwKeys.KEY_ESCAPE, 1.2f) { pickKey(GlfwKeys.KEY_ESCAPE) }
                listOf(
                    GlfwKeys.KEY_F1, GlfwKeys.KEY_F2, GlfwKeys.KEY_F3, GlfwKeys.KEY_F4,
                    GlfwKeys.KEY_F5, GlfwKeys.KEY_F6, GlfwKeys.KEY_F7, GlfwKeys.KEY_F8,
                    GlfwKeys.KEY_F9, GlfwKeys.KEY_F10, GlfwKeys.KEY_F11, GlfwKeys.KEY_F12
                ).forEach { c -> addKey(GlfwKeys.shortLabel(c), c) { pickKey(c) } }
            })
            wrap.addView(rowOf {
                addKey("`", GlfwKeys.KEY_GRAVE_ACCENT) { pickKey(GlfwKeys.KEY_GRAVE_ACCENT) }
                listOf(
                    GlfwKeys.KEY_1, GlfwKeys.KEY_2, GlfwKeys.KEY_3, GlfwKeys.KEY_4, GlfwKeys.KEY_5,
                    GlfwKeys.KEY_6, GlfwKeys.KEY_7, GlfwKeys.KEY_8, GlfwKeys.KEY_9, GlfwKeys.KEY_0
                ).forEach { c -> addKey(GlfwKeys.shortLabel(c), c) { pickKey(c) } }
                addKey("-", GlfwKeys.KEY_MINUS) { pickKey(GlfwKeys.KEY_MINUS) }
                addKey("=", GlfwKeys.KEY_EQUAL) { pickKey(GlfwKeys.KEY_EQUAL) }
                addKey("Bksp", GlfwKeys.KEY_BACKSPACE, 1.5f) { pickKey(GlfwKeys.KEY_BACKSPACE) }
            })
            wrap.addView(rowOf {
                addKey("Tab", GlfwKeys.KEY_TAB, 1.3f) { pickKey(GlfwKeys.KEY_TAB) }
                "QWERTYUIOP".forEach { ch ->
                    val c = GlfwKeys.KEY_A + (ch - 'A')
                    addKey(ch.toString(), c) { pickKey(c) }
                }
                addKey("[", GlfwKeys.KEY_LEFT_BRACKET) { pickKey(GlfwKeys.KEY_LEFT_BRACKET) }
                addKey("]", GlfwKeys.KEY_RIGHT_BRACKET) { pickKey(GlfwKeys.KEY_RIGHT_BRACKET) }
                addKey("\\", GlfwKeys.KEY_BACKSLASH, 1.2f) { pickKey(GlfwKeys.KEY_BACKSLASH) }
            })
            wrap.addView(rowOf {
                addKey("Caps", GlfwKeys.KEY_CAPS_LOCK, 1.4f) { pickKey(GlfwKeys.KEY_CAPS_LOCK) }
                "ASDFGHJKL".forEach { ch ->
                    val c = GlfwKeys.KEY_A + (ch - 'A')
                    addKey(ch.toString(), c) { pickKey(c) }
                }
                addKey(";", GlfwKeys.KEY_SEMICOLON) { pickKey(GlfwKeys.KEY_SEMICOLON) }
                addKey("'", GlfwKeys.KEY_APOSTROPHE) { pickKey(GlfwKeys.KEY_APOSTROPHE) }
                addKey("Enter", GlfwKeys.KEY_ENTER, 1.5f) { pickKey(GlfwKeys.KEY_ENTER) }
            })
            wrap.addView(rowOf {
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
            wrap.addView(rowOf {
                addKey("Ctrl", GlfwKeys.KEY_LEFT_CONTROL, 1.2f) { pickKey(GlfwKeys.KEY_LEFT_CONTROL) }
                addKey("Win", GlfwKeys.KEY_LEFT_SUPER) { pickKey(GlfwKeys.KEY_LEFT_SUPER) }
                addKey("Alt", GlfwKeys.KEY_LEFT_ALT) { pickKey(GlfwKeys.KEY_LEFT_ALT) }
                addKey("空格", GlfwKeys.KEY_SPACE, 3.5f) { pickKey(GlfwKeys.KEY_SPACE, "空格") }
                addKey("Alt", GlfwKeys.KEY_RIGHT_ALT) { pickKey(GlfwKeys.KEY_RIGHT_ALT) }
                addKey("Menu", GlfwKeys.KEY_MENU) { pickKey(GlfwKeys.KEY_MENU) }
                addKey("Ctrl", GlfwKeys.KEY_RIGHT_CONTROL, 1.2f) { pickKey(GlfwKeys.KEY_RIGHT_CONTROL) }
            })
            wrap.addView(rowOf {
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
            wrap.addView(rowOf {
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

            val hScroll = HorizontalScrollView(context).apply {
                isFillViewport = false
                addView(wrap)
            }
            return ScrollView(context).apply { addView(hScroll) }
        }

        fun comboPanel(): View {
            val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val mods = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            fun mod(title: String, get: () -> Boolean, set: (Boolean) -> Unit) {
                val b = keyBtn(title, 1f)
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
            wrap.addView(rowOf {
                listOf(
                    GlfwKeys.KEY_1, GlfwKeys.KEY_2, GlfwKeys.KEY_3, GlfwKeys.KEY_4, GlfwKeys.KEY_5,
                    GlfwKeys.KEY_6, GlfwKeys.KEY_7, GlfwKeys.KEY_8, GlfwKeys.KEY_9, GlfwKeys.KEY_0
                ).forEach { c -> addKey(GlfwKeys.shortLabel(c), c) { pickKey(c) } }
            })
            wrap.addView(rowOf {
                "QWERTYUIOP".forEach { ch ->
                    val c = GlfwKeys.KEY_A + (ch - 'A')
                    addKey(ch.toString(), c) { pickKey(c) }
                }
            })
            wrap.addView(rowOf {
                "ASDFGHJKL".forEach { ch ->
                    val c = GlfwKeys.KEY_A + (ch - 'A')
                    addKey(ch.toString(), c) { pickKey(c) }
                }
            })
            wrap.addView(rowOf {
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
            return ScrollView(context).apply { addView(wrap) }
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
        holdCheck.setOnCheckedChangeListener { _, _ -> refreshPreview() }

        root.addView(modeTabs)
        root.addView(holdCheck)
        root.addView(preview)
        root.addView(contentHost)

        dialog = AlertDialog.Builder(context)
            .setTitle("添加按键")
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog?.show()
        showMode(1)
    }

    /** Fixed-height host so the dialog does not jump when switching tabs. */
    private class FrameMatch(context: Context, heightPx: Int) : LinearLayout(context) {
        init {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, heightPx)
        }
    }
}
