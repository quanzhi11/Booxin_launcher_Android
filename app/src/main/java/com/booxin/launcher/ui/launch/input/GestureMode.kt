package com.booxin.launcher.ui.launch.input

/**
 * In-world (grabbed) screen-tap mode.
 *
 * BUILD: short tap / light touch = right click (place / use). No long-press.
 * FIGHT: short tap / light touch = left click (attack). No long-press.
 * Dragging the finger always looks around and does not click.
 */
enum class GestureMode {
    BUILD,
    FIGHT;

    companion object {
        fun fromName(name: String?, fallback: GestureMode = BUILD): GestureMode =
            entries.firstOrNull { it.name == name } ?: fallback
    }
}
