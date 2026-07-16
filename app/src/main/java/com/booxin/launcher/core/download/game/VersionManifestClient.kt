package com.booxin.launcher.core.download.game

import com.booxin.launcher.core.download.DownloadProviders
import com.booxin.launcher.core.net.FileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VersionManifestClient(
    private val downloader: FileDownloader = FileDownloader()
) {

    suspend fun fetchManifest(): Result<VersionManifest> = withContext(Dispatchers.IO) {
        runCatching {
            val provider = DownloadProviders.current()
            var lastError: Throwable? = null
            for (url in provider.versionListCandidates()) {
                val text = downloader.downloadText(url)
                if (text.isSuccess) {
                    return@runCatching GameJsonParser.parseManifest(text.getOrThrow())
                }
                lastError = text.exceptionOrNull()
            }
            throw lastError ?: IllegalStateException("无法获取版本列表")
        }
    }

    suspend fun fetchVersionJson(url: String): Result<ResolvedVersion> = withContext(Dispatchers.IO) {
        runCatching {
            val provider = DownloadProviders.current()
            var lastError: Throwable? = null
            for (candidate in provider.candidateUrls(url)) {
                val text = downloader.downloadText(candidate)
                if (text.isSuccess) {
                    return@runCatching GameJsonParser.parseVersionJson(text.getOrThrow())
                }
                lastError = text.exceptionOrNull()
            }
            throw lastError ?: IllegalStateException("无法下载 version.json")
        }
    }
}
