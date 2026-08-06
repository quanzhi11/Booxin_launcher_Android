package com.booxin.launcher.ui.launch.input

/** 抓取视角时：BUILD 右键 / FIGHT 左键 / COMBINED 看准星与手持。 */
enum class GestureMode {
    BUILD,
    FIGHT,
    COMBINED;

    companion object {
        fun fromName(name: String?, fallback: GestureMode = BUILD): GestureMode =
            entries.firstOrNull { it.name == name } ?: fallback
    }
}
