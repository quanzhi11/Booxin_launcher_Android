package com.booxin.launcher.ui.launch.input

/**
 * Grabbed look gestures:
 * - [COMBINED] 综合：短按=右键，长按=按住左键
 * - [FIGHT] 战斗：短按=左键，长按=按住左键
 * - [AA] AA/按键：屏幕滑动只转视角；攻击/放置仅用虚拟按键
 */
enum class GestureMode {
    COMBINED,
    FIGHT,
    AA;

    /** Screen taps / long-press should not inject mouse clicks. */
    val lookOnly: Boolean
        get() = this == AA

    companion object {
        fun fromName(name: String?, fallback: GestureMode = COMBINED): GestureMode {
            if (name.isNullOrBlank()) return fallback
            // Legacy prefs / saves used BUILD for the combined scheme.
            if (name == "BUILD" || name == "COMBINED") return COMBINED
            if (name == "LOOK_ONLY" || name == "BUTTON" || name == "AA") return AA
            return entries.firstOrNull { it.name == name } ?: fallback
        }
    }
}
