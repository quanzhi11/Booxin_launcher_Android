package com.booxin.launcher.core.version

import com.booxin.launcher.core.LauncherPaths
import java.io.File

data class VersionModFile(
    val file: File,
    val displayName: String,
    val enabled: Boolean
)

object VersionModsManager {
    fun list(versionId: String): List<VersionModFile> {
        val modsDir = File(LauncherPaths.versionsDir, "$versionId/mods")
        if (!modsDir.isDirectory) return emptyList()
        return modsDir.listFiles()
            ?.filter { it.isFile }
            ?.mapNotNull { file ->
                val name = file.name
                when {
                    name.endsWith(".jar", ignoreCase = true) -> {
                        VersionModFile(file, name, true)
                    }
                    name.endsWith(".jar.disabled", ignoreCase = true) -> {
                        VersionModFile(
                            file = file,
                            displayName = name.removeSuffix(".disabled"),
                            enabled = false
                        )
                    }
                    else -> null
                }
            }
            ?.sortedBy { it.displayName.lowercase() }
            .orEmpty()
    }

    fun toggle(mod: VersionModFile): Result<Unit> = runCatching {
        val src = mod.file
        if (!src.isFile) error("模组文件不存在: ${src.name}")
        val target = if (mod.enabled) {
            File(src.parentFile, src.name + ".disabled")
        } else {
            val enabledName = if (src.name.endsWith(".disabled")) {
                src.name.removeSuffix(".disabled")
            } else {
                src.name
            }
            File(src.parentFile, enabledName)
        }
        if (target.exists()) error("目标文件已存在: ${target.name}")
        if (!src.renameTo(target)) {
            src.copyTo(target, overwrite = false)
            if (!src.delete()) {
                target.delete()
                error("无法切换模组状态: ${src.name}")
            }
        }
    }

    fun uninstall(mod: VersionModFile): Result<Unit> = runCatching {
        if (!mod.file.isFile) error("模组文件不存在: ${mod.displayName}")
        if (!mod.file.delete()) error("删除失败: ${mod.displayName}")
    }
}
