package com.booxin.launcher.ui.launch.input

/**
 * Presets the user can add from the layout editor.
 */
object ControlCatalog {

    data class Entry(
        val label: String,
        val kind: ControlButtonSpec.Kind,
        val code: Int,
        val sizeDp: Int = 52
    )

    val entries: List<Entry> = listOf(
        Entry("键盘", ControlButtonSpec.Kind.SOFT_KEYBOARD, 0, 48),
        Entry("跳", ControlButtonSpec.Kind.KEY_HOLD, GlfwKeys.KEY_SPACE, 64),
        Entry("Shift", ControlButtonSpec.Kind.KEY_HOLD, GlfwKeys.KEY_LEFT_SHIFT),
        Entry("Ctrl", ControlButtonSpec.Kind.KEY_HOLD, GlfwKeys.KEY_LEFT_CONTROL),
        Entry("E", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_E),
        Entry("T", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_T),
        Entry("Q", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_Q),
        Entry("ESC", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_ESCAPE, 48),
        Entry("Tab", ControlButtonSpec.Kind.KEY_HOLD, GlfwKeys.KEY_TAB, 48),
        Entry("F5", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_F5, 48),
        Entry("Enter", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_ENTER, 48),
        Entry("左键", ControlButtonSpec.Kind.MOUSE_HOLD, GlfwKeys.MOUSE_LEFT, 56),
        Entry("右键", ControlButtonSpec.Kind.MOUSE_HOLD, GlfwKeys.MOUSE_RIGHT, 56),
        Entry("左键·跟手", ControlButtonSpec.Kind.MOUSE_FOLLOW, GlfwKeys.MOUSE_LEFT, 58),
        Entry("右键·跟手", ControlButtonSpec.Kind.MOUSE_FOLLOW, GlfwKeys.MOUSE_RIGHT, 54),
        Entry("跳·跟手", ControlButtonSpec.Kind.KEY_FOLLOW, GlfwKeys.KEY_SPACE, 66),
        Entry("▲", ControlButtonSpec.Kind.SCROLL, 1, 44),
        Entry("▼", ControlButtonSpec.Kind.SCROLL, -1, 44),
        Entry("1", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_1, 44),
        Entry("2", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_2, 44),
        Entry("3", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_3, 44),
        Entry("4", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_4, 44),
        Entry("5", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_5, 44),
        Entry("6", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_6, 44),
        Entry("7", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_7, 44),
        Entry("8", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_8, 44),
        Entry("9", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_9, 44),
    )

    fun softKeyboardButton(
        x: Float = 0.94f,
        y: Float = 0.30f,
        sizeDp: Int = 48
    ): ControlButtonSpec = ControlButtonSpec(
        id = "kbd",
        label = "键盘",
        kind = ControlButtonSpec.Kind.SOFT_KEYBOARD,
        code = 0,
        x = x,
        y = y,
        sizeDp = sizeDp
    )

    fun defaultLayout(): List<ControlButtonSpec> = listOf(
        // Top-right utility
        ControlButtonSpec("esc", "ESC", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_ESCAPE, 0.94f, 0.06f, 44),
        ControlButtonSpec("chat", "T", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_T, 0.94f, 0.14f, 48),
        ControlButtonSpec("inv", "E", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_E, 0.94f, 0.22f, 48),
        softKeyboardButton(0.94f, 0.30f, 48),
        // Right combat / interact column — labels are concrete keys (jump stays 跳)
        ControlButtonSpec("lmb", "左键", ControlButtonSpec.Kind.MOUSE_HOLD, GlfwKeys.MOUSE_LEFT, 0.90f, 0.58f, 58),
        ControlButtonSpec("rmb", "右键", ControlButtonSpec.Kind.MOUSE_HOLD, GlfwKeys.MOUSE_RIGHT, 0.78f, 0.58f, 54),
        ControlButtonSpec("jump", "跳", ControlButtonSpec.Kind.KEY_HOLD, GlfwKeys.KEY_SPACE, 0.90f, 0.74f, 66),
        ControlButtonSpec("sneak", "Shift", ControlButtonSpec.Kind.KEY_HOLD, GlfwKeys.KEY_LEFT_SHIFT, 0.78f, 0.86f, 52),
        // Hotbar helpers (left of jump column)
        ControlButtonSpec("scup", "▲", ControlButtonSpec.Kind.SCROLL, 1, 0.66f, 0.74f, 42),
        ControlButtonSpec("scdn", "▼", ControlButtonSpec.Kind.SCROLL, -1, 0.66f, 0.86f, 42),
        ControlButtonSpec("drop", "Q", ControlButtonSpec.Kind.KEY_TAP, GlfwKeys.KEY_Q, 0.54f, 0.90f, 44),
    )

    /**
     * Old functional Chinese labels → concrete key names for stock ids.
     * Jump (`jump`) and custom renames are left alone.
     */
    fun migratedStockLabel(id: String, label: String): String {
        val trimmed = label.trim()
        return when (id) {
            "lmb" -> when (trimmed) {
                "攻击", "攻击·跟手" -> "左键"
                else -> label
            }
            "rmb" -> when (trimmed) {
                "使用", "使用·跟手" -> "右键"
                else -> label
            }
            "sneak" -> when (trimmed) {
                "潜", "潜行" -> "Shift"
                else -> label
            }
            else -> label
        }
    }
}
