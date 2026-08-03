package com.booxin.launcher.core.version

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.net.FileDownloader
import java.io.File
import java.net.URI

data class VersionModFile(
    val file: File,
    val displayName: String,
    val enabled: Boolean
)

object VersionModsManager {
    fun modsDir(versionId: String): File =
        File(LauncherPaths.versionsDir, "$versionId/mods").also { it.mkdirs() }

    fun list(versionId: String): List<VersionModFile> {
        val modsDir = modsDir(versionId)
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

    /** Download a remote jar/zip into this version's mods folder. */
    suspend fun downloadFromUrl(
        versionId: String,
        url: String,
        downloader: FileDownloader = FileDownloader(),
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Result<File> {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return Result.failure(IllegalArgumentException("URL 必须以 http:// 或 https:// 开头"))
        }
        val fileName = fileNameFromUrl(trimmed)
        val dest = File(modsDir(versionId), fileName)
        return downloader.download(trimmed, dest, onProgress)
    }

    fun fileNameFromUrl(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
            .substringBefore('?')
            .substringAfterLast('/')
            .trim()
        val decoded = path.ifBlank { "mod-${System.currentTimeMillis()}.jar" }
        val safe = decoded.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return when {
            safe.endsWith(".jar", ignoreCase = true) -> safe
            safe.endsWith(".zip", ignoreCase = true) -> safe
            else -> "$safe.jar"
        }
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
