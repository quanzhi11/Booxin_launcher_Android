package com.booxin.launcher.ui.launch.input

/**
 * Grabbed look gestures:
 * - [COMBINED] 综合：短按=右键，长按=按住左键
 * - [FIGHT] 战斗：短按=左键，长按=按住左键
 */
enum class GestureMode {
    COMBINED,
    FIGHT;

    companion object {
        fun fromName(name: String?, fallback: GestureMode = COMBINED): GestureMode {
            if (name.isNullOrBlank()) return fallback
            // Legacy prefs / saves used BUILD for the combined scheme.
            if (name == "BUILD" || name == "COMBINED") return COMBINED
            return entries.firstOrNull { it.name == name } ?: fallback
        }
    }
}
