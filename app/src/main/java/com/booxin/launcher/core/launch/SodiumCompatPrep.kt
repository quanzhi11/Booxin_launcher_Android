package com.booxin.launcher.core.launch

import com.booxin.launcher.core.version.VersionModsManager
import java.io.File

/**
 * Sodium notes (no helper-mod injection).
 *
 * LWJGL issue#2561 is already bypassed via `-Dsodium.checks.issue2561=false`.
 * Also removes Podium jars we previously auto-copied into mods/.
 */
object SodiumCompatPrep {

    private val AUTO_INSTALLED = setOf(
        "podium-fabric-1.1.1.jar",
        "podium-neoforge-1.1.1.jar",
    )

    /**
     * @return human-readable status for launch log, or null if nothing to report.
     */
    fun prepare(versionId: String): String? {
        val mods = VersionModsManager.modsDir(versionId)
        val jars = mods.listFiles()?.filter { it.isFile } ?: emptyList()
        if (!jars.any { looksLikeSodium(it.name) }) return null

        val removed = mutableListOf<String>()
        for (name in AUTO_INSTALLED) {
            val f = File(mods, name)
            if (f.isFile && f.delete()) removed += name
        }

        return buildString {
            append("检测到 Sodium：已跳过 issue#2561 检测（-Dsodium.checks.issue2561=false）")
            if (removed.isNotEmpty()) {
                append("；已移除先前自动安装的 Podium：")
                append(removed.joinToString(", "))
            }
        }
    }

    private fun looksLikeSodium(name: String): Boolean {
        val n = name.lowercase()
        if (n.endsWith(".disabled")) return false
        return n.startsWith("sodium") && n.endsWith(".jar")
    }
}
