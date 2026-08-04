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
            val urls = DownloadProviders.current().versionListCandidates()
            val text = downloader.downloadText(urls).getOrThrow()
            GameJsonParser.parseManifest(text)
        }
    }

    suspend fun fetchVersionJson(url: String): Result<ResolvedVersion> = withContext(Dispatchers.IO) {
        runCatching {
            val urls = DownloadProviders.current().candidateUrls(url)
            val text = downloader.downloadText(urls).getOrThrow()
            GameJsonParser.parseVersionJson(text)
        }
    }
}
