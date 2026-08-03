package com.booxin.launcher.core.launch

import android.content.Context
import com.booxin.launcher.core.version.VersionModsManager
import java.io.File

/**
 * Newer Sodium refuses some Android hosts. Bundle Podium to skip that check
 * until our runtime fingerprint is distinct enough on its own.
 */
object SodiumPodiumInstaller {

    private const val ASSET_FABRIC = "app_runtime/mods/podium-fabric-1.1.1.jar"
    private const val ASSET_NEOFORGE = "app_runtime/mods/podium-neoforge-1.1.1.jar"
    private const val DEST_FABRIC = "podium-fabric-1.1.1.jar"
    private const val DEST_NEOFORGE = "podium-neoforge-1.1.1.jar"

    /**
     * @return human-readable status for launch log, or null if nothing to do.
     */
    fun ensure(context: Context, versionId: String): String? {
        val mods = VersionModsManager.modsDir(versionId)
        val jars = mods.listFiles()?.filter { it.isFile } ?: emptyList()
        if (!jars.any { looksLikeSodium(it.name) }) return null
        if (jars.any { looksLikePodium(it.name) && !it.name.endsWith(".disabled", true) }) {
            return "已检测到 Podium（Sodium Android 兼容）"
        }

        val id = versionId.lowercase()
        val (asset, destName) = when {
            "neoforge" in id -> ASSET_NEOFORGE to DEST_NEOFORGE
            "fabric" in id || "quilt" in id -> ASSET_FABRIC to DEST_FABRIC
            else -> {
                // Vanilla / Forge / unknown: Fabric Podium won't load; skip quietly.
                // NeoForge id without "neoforge" is rare; Forge uses different Sodium.
                return "检测到 Sodium，但当前版本不是 Fabric/Quilt/NeoForge，跳过 Podium"
            }
        }

        val dest = File(mods, destName)
        return runCatching {
            context.assets.open(asset).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            "已自动安装 Podium（解除 Sodium 对 Android 运行时的拦截）→ ${dest.name}"
        }.getOrElse {
            "自动安装 Podium 失败: ${it.message}"
        }
    }

    private fun looksLikeSodium(name: String): Boolean {
        val n = name.lowercase()
        if (n.endsWith(".disabled")) return false
        return n.startsWith("sodium") && n.endsWith(".jar")
    }

    private fun looksLikePodium(name: String): Boolean {
        val n = name.lowercase().removeSuffix(".disabled")
        return n.startsWith("podium") && n.endsWith(".jar")
    }
}
