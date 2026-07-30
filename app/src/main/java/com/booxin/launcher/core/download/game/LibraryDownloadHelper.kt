package com.booxin.launcher.core.download.game

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.DownloadProviders
import com.booxin.launcher.core.net.FileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class LibraryDownloadHelper(
    private val downloader: FileDownloader = FileDownloader()
) {
    suspend fun downloadLibraries(
        libraries: JSONArray,
        versionId: String,
        onProgress: (completed: Int, total: Int, message: String) -> Unit = { _, _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val resolved = GameJsonParser.parseVersionJson(
            JSONObject()
                .put("id", versionId)
                .put("libraries", libraries)
                .toString()
        ).libraries
        if (resolved.isEmpty()) return@withContext
        downloadResolved(resolved, versionId, onProgress)
    }

    suspend fun downloadVersionLibraries(
        versionId: String,
        onProgress: (completed: Int, total: Int, message: String) -> Unit = { _, _, _ -> }
    ) {
        val merged = VersionJsonMerger.merge(versionId)
            ?: error("缺少版本 JSON: $versionId")
        downloadLibraries(
            merged.optJSONArray("libraries") ?: JSONArray(),
            versionId,
            onProgress
        )
    }

    private suspend fun downloadResolved(
        libraries: List<ResolvedLibrary>,
        versionId: String,
        onProgress: (completed: Int, total: Int, message: String) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(LIBRARY_CONCURRENCY)
        val completed = AtomicInteger(0)
        val total = libraries.size
        libraries.map { lib ->
            async {
                semaphore.withPermit {
                    val destination = File(LauncherPaths.librariesDir, lib.path)
                    if (!Digests.matchesSha1(destination, lib.sha1)) {
                        ensureLibrary(lib, destination)
                    }
                    val done = completed.incrementAndGet()
                    onProgress(done, total, "下载依赖 $done / $total")
                }
            }
        }.awaitAll()
    }

    private suspend fun ensureLibrary(lib: ResolvedLibrary, destination: File) {
        if (Digests.matchesSha1(destination, lib.sha1)) return
        if (lib.url.isBlank()) {
            if (destination.isFile && destination.length() > 0L) return
            error("缺少本地库（需 Forge 安装器生成）: ${lib.name}")
        }
        downloadVerified(lib.url, destination, lib.sha1, lib.name)
    }

    private suspend fun downloadVerified(
        rawUrl: String,
        destination: File,
        sha1: String?,
        label: String
    ) = withContext(Dispatchers.IO) {
        if (Digests.matchesSha1(destination, sha1)) return@withContext
        val candidates = DownloadProviders.current().candidateUrls(rawUrl)
        var lastError: Throwable? = null
        for (url in candidates) {
            val result = downloader.download(url, destination)
            if (result.isSuccess) {
                if (!Digests.matchesSha1(destination, sha1)) {
                    destination.delete()
                    lastError = IllegalStateException("SHA-1 校验失败: ${destination.name}")
                    continue
                }
                return@withContext
            }
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: IOException("下载失败: $label")
    }

    companion object {
        private const val LIBRARY_CONCURRENCY = 6
    }
}
