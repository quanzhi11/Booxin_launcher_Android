package com.booxin.launcher.core.runtime

/**
 * How aggressively BooxinGlues may leave the pure Path A (MIT/BSD) stack.
 *
 * - [PATH_A_ONLY]: never MobileGlues (LGPL).
 * - [MAX_COMPAT]: Path A first, then Zink/ANGLE, then MobileGlues for maximum mod coverage.
 */
enum class BooxinGlCompatMode {
    PATH_A_ONLY,
    MAX_COMPAT;

    val displayLabel: String
        get() = when (this) {
            PATH_A_ONLY -> "仅自研 Path A"
            MAX_COMPAT -> "最大兼容（推荐）"
        }
}
