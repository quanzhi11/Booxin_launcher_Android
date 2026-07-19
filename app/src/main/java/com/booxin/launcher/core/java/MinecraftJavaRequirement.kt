package com.booxin.launcher.core.java

import com.booxin.launcher.core.LauncherPaths
import java.io.File
import org.json.JSONObject

/**
 * Maps a Minecraft version id to the required Java major version.
 *
 * Priority:
 * 1. [version.json] `javaVersion.majorVersion` when present
 * 2. Heuristic fallback for legacy ids
 */
object MinecraftJavaRequirement {

    fun requiredMajor(mcVersionId: String): Int {
        readFromVersionJson(mcVersionId)?.let { return it }
        return heuristicMajor(mcVersionId)
    }

    fun readFromVersionJson(mcVersionId: String): Int? {
        val jsonFile = File(LauncherPaths.versionsDir, "$mcVersionId/$mcVersionId.json")
        if (!jsonFile.isFile) return null
        return runCatching {
            val root = JSONObject(jsonFile.readText())
            root.optJSONObject("javaVersion")
                ?.optInt("majorVersion", -1)
                ?.takeIf { it > 0 }
        }.getOrNull()
    }

    private fun heuristicMajor(mcVersionId: String): Int {
        val parsed = parseVersion(mcVersionId) ?: return 17
        return when {
            // Year-based releases like 26.2 ship with Java 25+.
            parsed.first >= 26 -> 25
            isAtLeast(parsed, 1, 20, 5) -> 21
            isAtLeast(parsed, 1, 17, 0) -> 17
            else -> 8
        }
    }

    /**
     * Parses ids like "1.20.4", "26.2", "1.16.5-forge-...".
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
