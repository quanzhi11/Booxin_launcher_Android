package com.booxin.launcher.core.java

/**
 * Maps a Minecraft version id to the required Java major version.
 *
 * Rules (Minecraft Java Edition):
 * - < 1.17          → Java 8
 * - 1.17 ~ 1.20.4   → Java 17
 * - ≥ 1.20.5        → Java 21
 */
object MinecraftJavaRequirement {

    fun requiredMajor(mcVersionId: String): Int {
        val parsed = parseVersion(mcVersionId) ?: return 17
        return when {
            isAtLeast(parsed, 1, 20, 5) -> 21
            isAtLeast(parsed, 1, 17, 0) -> 17
            else -> 8
        }
    }

    /**
     * Parses ids like "1.20.4", "1.21", "1.16.5-forge-...".
     * Snapshots / non-semver ids fall back to null (caller uses default).
     */
    fun parseVersion(id: String): Triple<Int, Int, Int>? {
        val core = id.substringBefore('-').substringBefore('_')
        val parts = core.split('.')
        if (parts.size < 2) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = parts.getOrNull(2)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        return Triple(major, minor, patch)
    }

    private fun isAtLeast(version: Triple<Int, Int, Int>, major: Int, minor: Int, patch: Int): Boolean {
        val (a, b, c) = version
        if (a != major) return a > major
        if (b != minor) return b > minor
        return c >= patch
    }
}
