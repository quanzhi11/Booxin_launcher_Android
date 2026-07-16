package com.booxin.launcher.core.download.game

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.DownloadProviders
import com.booxin.launcher.core.net.FileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Vanilla Minecraft install pipeline modeled after FCL GameInstallTask:
 * version.json → client.jar → libraries → assets.
 */
class VanillaGameInstaller(
    private val downloader: FileDownloader = FileDownloader(),
    private val manifestClient: VersionManifestClient = VersionManifestClient(downloader)
) {

    private val mutex = Mutex()
    private val _progress = MutableStateFlow<GameInstallProgress?>(null)
    val progress: StateFlow<GameInstallProgress?> = _progress.asStateFlow()

    fun isInstalled(versionId: String): Boolean {
        val jar = File(LauncherPaths.versionsDir, "$versionId/$versionId.jar")
        val json = File(LauncherPaths.versionsDir, "$versionId/$versionId.json")
        return jar.exists() && jar.length() > 0L && json.exists()
    }

    suspend fun install(versionId: String, versionJsonUrl: String? = null): Result<Unit> =
        mutex.withLock {
            runCatching {
                emit(versionId, GameInstallPhase.MANIFEST, "正在解析版本元数据…")

                val remoteUrl = versionJsonUrl ?: resolveVersionUrl(versionId)
                val resolved = manifestClient.fetchVersionJson(remoteUrl).getOrThrow()

                emit(versionId, GameInstallPhase.VERSION_JSON, "正在保存 version.json…")
                val versionRoot = File(LauncherPaths.versionsDir, versionId).also { it.mkdirs() }
                File(versionRoot, "$versionId.json").writeText(resolved.rawJson)

                val client = resolved.client
                    ?: error("版本 $versionId 缺少 client 下载信息")
                emit(versionId, GameInstallPhase.CLIENT, "正在下载客户端…")
                downloadVerified(
                    rawUrl = client.url,
                    destination = File(versionRoot, "$versionId.jar"),
                    sha1 = client.sha1,
                    versionId = versionId,
                    phase = GameInstallPhase.CLIENT,
                    message = "下载客户端 $versionId.jar"
                )

                emit(versionId, GameInstallPhase.LIBRARIES, "正在下载依赖库…", 0, resolved.libraries.size)
                downloadAll(
                    items = resolved.libraries.map { lib ->
                        DownloadItem(
                            rawUrl = lib.url,
                            destination = File(LauncherPaths.librariesDir, lib.path),
                            sha1 = lib.sha1,
                            label = lib.name
                        )
                    },
                    versionId = versionId,
                    phase = GameInstallPhase.LIBRARIES,
                    concurrency = LIBRARY_CONCURRENCY
                )

                val assetIndex = resolved.assetIndex
                if (assetIndex != null) {
                    emit(versionId, GameInstallPhase.ASSETS, "正在下载资源索引…")
                    val indexFile = File(LauncherPaths.assetsDir, "indexes/${assetIndex.id}.json")
                    downloadVerified(
                        rawUrl = assetIndex.url,
                        destination = indexFile,
                        sha1 = assetIndex.sha1,
                        versionId = versionId,
                        phase = GameInstallPhase.ASSETS,
                        message = "下载资产索引 ${assetIndex.id}"
                    )
                    val objects = GameJsonParser.parseAssetIndex(indexFile.readText())
                    emit(versionId, GameInstallPhase.ASSETS, "正在下载游戏资源…", 0, objects.size)
                    val provider = DownloadProviders.current()
                    downloadAll(
                        items = objects.map { obj ->
                            DownloadItem(
                                rawUrl = obj.hashPath,
                                destination = File(LauncherPaths.assetsDir, "objects/${obj.hashPath}"),
                                sha1 = obj.hash,
                                label = obj.hash,
                                urlCandidates = provider.assetCandidates(obj.hashPath)
                            )
                        },
                        versionId = versionId,
                        phase = GameInstallPhase.ASSETS,
                        concurrency = ASSET_CONCURRENCY
                    )
                }

                emit(versionId, GameInstallPhase.DONE, "安装完成")
            }.onFailure { error ->
                emit(
                    versionId,
                    GameInstallPhase.FAILED,
                    error.message ?: "安装失败"
                )
            }.map { }
        }

    private suspend fun resolveVersionUrl(versionId: String): String {
        val manifest = manifestClient.fetchManifest().getOrThrow()
        return manifest.versions.firstOrNull { it.id == versionId }?.url
            ?: error("版本列表中未找到 $versionId")
    }

    private suspend fun downloadAll(
        items: List<DownloadItem>,
        versionId: String,
        phase: GameInstallPhase,
        concurrency: Int
    ) = coroutineScope {
        if (items.isEmpty()) return@coroutineScope
        val semaphore = Semaphore(concurrency)
        val completed = AtomicInteger(0)
        val total = items.size
        items.map { item ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (!Digests.matchesSha1(item.destination, item.sha1)) {
                        downloadVerified(
                            rawUrl = item.rawUrl,
                            destination = item.destination,
                            sha1 = item.sha1,
                            versionId = versionId,
                            phase = phase,
                            message = "下载 ${item.label}",
                            urlCandidates = item.urlCandidates
                        )
                    }
                    val done = completed.incrementAndGet()
                    emit(versionId, phase, "已完成 $done / $total", done, total)
                }
            }
        }.awaitAll()
    }

    private suspend fun downloadVerified(
        rawUrl: String,
        destination: File,
        sha1: String?,
        versionId: String,
        phase: GameInstallPhase,
        message: String,
        urlCandidates: List<String>? = null
    ) = withContext(Dispatchers.IO) {
        if (Digests.matchesSha1(destination, sha1)) return@withContext

        val candidates = urlCandidates ?: DownloadProviders.current().candidateUrls(rawUrl)

        var lastError: Throwable? = null
        for (url in candidates) {
            val result = downloader.download(url, destination) { downloaded, total ->
                _progress.value = GameInstallProgress(
                    versionId = versionId,
                    phase = phase,
                    message = message,
                    downloadedBytes = downloaded,
                    totalBytes = total
                )
            }
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
        throw lastError ?: IOException("下载失败: $rawUrl")
    }

    private fun emit(
        versionId: String,
        phase: GameInstallPhase,
        message: String,
        completed: Int = 0,
        total: Int = 0
    ) {
        _progress.value = GameInstallProgress(
            versionId = versionId,
            phase = phase,
            message = message,
            completed = completed,
            total = total
        )
    }

    private data class DownloadItem(
        val rawUrl: String,
        val destination: File,
        val sha1: String?,
        val label: String,
        val urlCandidates: List<String>? = null
    )

    companion object {
        private const val LIBRARY_CONCURRENCY = 6
        private const val ASSET_CONCURRENCY = 12
    }
}
