package com.booxin.launcher.core.version

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.net.FileDownloader
import java.io.File
import java.net.URI

data class VersionResourcePackFile(
    val file: File,
    val displayName: String,
    val enabled: Boolean
)

/**
 * Per-version Minecraft resource packs under `versions/<id>/resourcepacks`.
 * Disable by renaming to `*.disabled` (Minecraft only loads `.zip` / folders).
 */
object VersionResourcePacksManager {
    fun resourcePacksDir(versionId: String): File =
        File(LauncherPaths.versionsDir, "$versionId/resourcepacks").also { it.mkdirs() }

    fun list(versionId: String): List<VersionResourcePackFile> =
        listIn(resourcePacksDir(versionId))

    fun listIn(dir: File): List<VersionResourcePackFile> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.mapNotNull { file -> toEntry(file) }
            ?.sortedBy { it.displayName.lowercase() }
            .orEmpty()
    }

    private fun toEntry(file: File): VersionResourcePackFile? {
        val name = file.name
        return when {
            file.isDirectory && !name.endsWith(".disabled", ignoreCase = true) ->
                VersionResourcePackFile(file, name, true)
            file.isDirectory && name.endsWith(".disabled", ignoreCase = true) ->
                VersionResourcePackFile(file, name.removeSuffix(".disabled"), false)
            file.isFile && name.endsWith(".zip", ignoreCase = true) ->
                VersionResourcePackFile(file, name, true)
            file.isFile && name.endsWith(".zip.disabled", ignoreCase = true) ->
                VersionResourcePackFile(file, name.removeSuffix(".disabled"), false)
            else -> null
        }
    }

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
        val dest = File(resourcePacksDir(versionId), fileName)
        return downloader.download(trimmed, dest, onProgress)
    }

    fun fileNameFromUrl(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
            .substringBefore('?')
            .substringAfterLast('/')
            .trim()
        val decoded = path.ifBlank { "resourcepack-${System.currentTimeMillis()}.zip" }
        val safe = decoded.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return when {
            safe.endsWith(".zip", ignoreCase = true) -> safe
            safe.endsWith(".jar", ignoreCase = true) -> safe // rare but valid as zip
            else -> "$safe.zip"
        }
    }

    fun toggle(pack: VersionResourcePackFile): Result<Unit> = runCatching {
        val src = pack.file
        if (!src.exists()) error("资源包不存在: ${src.name}")
        val target = if (pack.enabled) {
            File(src.parentFile, src.name + ".disabled")
        } else {
            val enabledName = if (src.name.endsWith(".disabled", ignoreCase = true)) {
                src.name.removeSuffix(".disabled")
            } else {
                src.name
            }
            File(src.parentFile, enabledName)
        }
        if (target.exists()) error("目标已存在: ${target.name}")
        if (!src.renameTo(target)) {
            if (src.isDirectory) {
                src.copyRecursively(target, overwrite = false)
                if (!src.deleteRecursively()) {
                    target.deleteRecursively()
                    error("无法切换资源包状态: ${src.name}")
                }
            } else {
                src.copyTo(target, overwrite = false)
                if (!src.delete()) {
                    target.delete()
                    error("无法切换资源包状态: ${src.name}")
                }
            }
        }
    }

    fun uninstall(pack: VersionResourcePackFile): Result<Unit> = runCatching {
        if (!pack.file.exists()) error("资源包不存在: ${pack.displayName}")
        val ok = if (pack.file.isDirectory) {
            pack.file.deleteRecursively()
        } else {
            pack.file.delete()
        }
        if (!ok) error("删除失败: ${pack.displayName}")
    }
}
