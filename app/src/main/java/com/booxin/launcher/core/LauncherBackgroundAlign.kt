package com.booxin.launcher.core

/**
 * How the launcher wallpaper video/image is fitted to the screen.
 */
enum class LauncherBackgroundAlignMode(val id: String) {
    /** OEM auto (vivo uses view-scale; others use buffer fill). */
    AUTO("auto"),
    /** Force stretch to fill (may distort). Best fix for “half screen” bugs. */
    STRETCH("stretch"),
    /** Keep aspect, crop overflow. */
    CROP("crop"),
    /** Stretch base + user scale / offset. */
    MANUAL("manual");

    companion object {
        fun fromId(raw: String?): LauncherBackgroundAlignMode {
            val id = raw?.trim().orEmpty()
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

data class LauncherBackgroundAlign(
    val mode: LauncherBackgroundAlignMode = LauncherBackgroundAlignMode.AUTO,
    /** Extra scale for MANUAL, 0.50 .. 3.00 (1 = 100%). */
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    /** Pan as fraction of cover size, -0.5 .. 0.5. */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)
