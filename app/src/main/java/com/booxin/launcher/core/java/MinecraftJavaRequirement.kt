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
        return readFromVersionJson(mcVersionId, visited = mutableSetOf())
    }

    private fun readFromVersionJson(mcVersionId: String, visited: MutableSet<String>): Int? {
        if (!visited.add(mcVersionId)) return null
        if (!LauncherPaths.isInitialized) return null
        val jsonFile = File(LauncherPaths.versionsDir, "$mcVersionId/$mcVersionId.json")
        if (!jsonFile.isFile) return null
        return runCatching {
            val root = JSONObject(jsonFile.readText())
            root.optJSONObject("javaVersion")
                ?.optInt("majorVersion", -1)
                ?.takeIf { it > 0 }
                ?: root.optString("inheritsFrom").ifBlank { null }?.let { parentId ->
                    // Fabric/Forge wrappers often omit javaVersion; use parent id / json.
                    readFromVersionJson(parentId, visited) ?: heuristicMajor(parentId).takeIf { it > 0 }
                }
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
     * Parses ids like "1.20.4", "26.2", "1.16.5-forge-...", "fabric-loader-0.14-1.16.5".
     */
    fun parseVersion(id: String): Triple<Int, Int, Int>? {
        val core = id.substringBefore('-').substringBefore('_')
        parseDotted(core)?.let { return it }
        // Non-leading ids: fabric-loader-0.14.22-1.16.5 → take last X.Y(.Z)
        val match = Regex("""(?<!\d)(\d+)\.(\d+)(?:\.(\d+))?""")
            .findAll(id)
            .lastOrNull()
            ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        // 同时出现时优先 Minecraft 的 1.x。
        if (major == 0) {
            val minecraftish = Regex("""(?<!\d)(1)\.(\d+)(?:\.(\d+))?""")
                .findAll(id)
                .lastOrNull()
            if (minecraftish != null) {
                return Triple(
                    1,
                    minecraftish.groupValues[2].toIntOrNull() ?: return null,
                    minecraftish.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
                )
            }
        }
        return Triple(major, minor, patch)
    }

    private fun parseDotted(core: String): Triple<Int, Int, Int>? {
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

    /**
     * Minecraft 26.3 Snapshot 4+ replaced GLFW with SDL3 for window/input.
     * Ids like "26.3-snapshot-6" parse as 26.3.
     */
    fun usesSdlWindowing(mcVersionId: String): Boolean {
        val parsed = parseVersion(mcVersionId) ?: return false
        if (parsed.first >= 27) return true
        return parsed.first == 26 && parsed.second >= 3
    }

    /** Java 8 JRE cannot load our default LWJGL jar (class file 61+). */
    fun needsJava8Lwjgl(javaMajor: Int): Boolean = javaMajor < 9

    /**
     * Pre-1.13 / LaunchWrapper-era clients use LWJGL2 Display, not GLFW preinit.
     * OptiFine 1.17+ launchwrapper-of still uses GLFW on Java 17+.
     */
    fun usesLegacyLwjglWindowing(mcVersionId: String, mainClass: String): Boolean {
        if ("launchwrapper" in mainClass.lowercase()) {
            val parsed = parseVersion(mcVersionId) ?: return true
            if (isAtLeast(parsed, 1, 17, 0)) return false
            return true
        }
        val parsed = parseVersion(mcVersionId) ?: return false
        return parsed.first == 1 && parsed.second < 13
    }

    /**
     * Ancient / pre-1.13 clients need holy GL4ES; MobileGlues / REL often fail to start.
     * Covers Beta/Alpha/Classic ids (b1.8.1, a1.2.6, c0.30, inf-*, rd-*).
     */
    fun needsGl4esRenderer(mcVersionId: String): Boolean {
        val parsed = parseVersion(mcVersionId)
        if (parsed != null) {
            if (parsed.first >= 26) return false
            if (parsed.first == 1) return parsed.second < 13
            // Classic 0.x
            if (parsed.first == 0) return true
        }
        val lower = mcVersionId.lowercase()
        return lower.startsWith("inf-") ||
            lower.startsWith("rd-") ||
            lower.startsWith("b1.") ||
            lower.startsWith("a1.") ||
            lower.startsWith("a0.") ||
            lower.startsWith("c0.") ||
            lower.startsWith("c1.")
    }
}
