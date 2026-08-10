package com.booxin.launcher.core.version

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.net.FileDownloader
import java.io.File

/**
 * Per-version shader packs under `versions/<id>/shaderpacks`.
 * Disable by renaming to `*.disabled` (Iris / OptiFine load `.zip` / folders).
 */
object VersionShaderPacksManager {
    fun shaderPacksDir(versionId: String): File =
        File(LauncherPaths.versionsDir, "$versionId/shaderpacks").also { it.mkdirs() }

    fun list(versionId: String): List<VersionResourcePackFile> =
        VersionResourcePacksManager.listIn(shaderPacksDir(versionId))

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
        val fileName = VersionResourcePacksManager.fileNameFromUrl(trimmed)
        val dest = File(shaderPacksDir(versionId), fileName)
        return downloader.download(trimmed, dest, onProgress)
    }

    fun toggle(pack: VersionResourcePackFile): Result<Unit> =
        VersionResourcePacksManager.toggle(pack)

    fun uninstall(pack: VersionResourcePackFile): Result<Unit> =
        VersionResourcePacksManager.uninstall(pack)
}
