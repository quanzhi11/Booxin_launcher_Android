package com.booxin.launcher.ui.launch.input

/** Grabbed look: BUILD = RMB tap, FIGHT = LMB tap. */
enum class GestureMode {
    BUILD,
    FIGHT;

    companion object {
        fun fromName(name: String?, fallback: GestureMode = BUILD): GestureMode =
            entries.firstOrNull { it.name == name } ?: fallback
    }
}
