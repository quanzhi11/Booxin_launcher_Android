package com.booxin.launcher.core

import com.booxin.launcher.R

enum class LauncherBackgroundTheme(
    val id: String,
    val titleRes: Int,
    val descRes: Int,
    val assetPath: String
) {
    YANG(
        id = "Yang",
        titleRes = R.string.bg_theme_yang,
        descRes = R.string.bg_theme_yang_desc,
        assetPath = "backgrounds/yang.mp4"
    ),
    END_POEM(
        id = "EndPoem",
        titleRes = R.string.bg_theme_end_poem,
        descRes = R.string.bg_theme_end_poem_desc,
        assetPath = "backgrounds/end_poem.mp4"
    ),
    UNDERWATER(
        id = "Underwater",
        titleRes = R.string.bg_theme_underwater,
        descRes = R.string.bg_theme_underwater_desc,
        assetPath = "backgrounds/underwater.mp4"
    ),
    CHUN_ZE(
        id = "ChunZe",
        titleRes = R.string.bg_theme_chun_ze,
        descRes = R.string.bg_theme_chun_ze_desc,
        assetPath = "backgrounds/chun_ze.mp4"
    );

    companion object {
        val DEFAULT = CHUN_ZE

        fun fromId(raw: String?): LauncherBackgroundTheme {
            if (raw.isNullOrBlank()) return DEFAULT
            // PC legacy name "Glass" → ChunZe
            if (raw.equals("Glass", ignoreCase = true)) return CHUN_ZE
            return entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: DEFAULT
        }
    }
}
