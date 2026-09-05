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
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Vanilla install: version.json → client.jar → libraries → assets. */
class VanillaGameInstaller(
    private val downloader: FileDownloader = FileDownloader(),
    private val manifestClient: VersionManifestClient = VersionManifestClient(downloader)
) {

    private val mutex = Mutex()
    /** 与安装锁分开，避免启动补资源死锁。 */
    private val assetsMutex = Mutex()
    private val _progress = MutableStateFlow<GameInstallProgress?>(null)
    val progress: StateFlow<GameInstallProgress?> = _progress.asStateFlow()

    fun isInstalled(versionId: String): Boolean {
        val jar = File(LauncherPaths.versionsDir, "$versionId/$versionId.jar")
        val json = File(LauncherPaths.versionsDir, "$versionId/$versionId.json")
        if (!jar.isFile || jar.length() <= 0L || !json.isFile) return false
        // Reject incomplete / wrong profiles (e.g. inheritsFrom stub without a real client).
        return runCatching {
            val root = JSONObject(json.readText())
            if (root.optString("inheritsFrom").isNotBlank()) return@runCatching false
            val id = root.optString("id")
            id.isBlank() || id == versionId
        }.getOrDefault(false)
    }

    /** Existence + size check against asset index objects (no SHA rehash). */
    fun assetsComplete(versionId: String): Boolean {
        val missing = missingAssetObjects(versionId) ?: return true
        return missing.isEmpty()
    }

    /**
     * Re-download missing asset objects for [versionId].
     * Existence-only check (no SHA rehash of the whole index every launch).
     * Uses a separate mutex so it cannot deadlock against [install].
     */
    suspend fun ensureAssets(versionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        assetsMutex.withLock {
            runCatching {
                val jsonFile = File(LauncherPaths.versionsDir, "$versionId/$versionId.json")
                if (!jsonFile.isFile) return@runCatching
                val root = JSONObject(jsonFile.readText())
                if (root.optString("inheritsFrom").isNotBlank()) return@runCatching
                val assetIndexObj = root.optJSONObject("assetIndex") ?: return@runCatching
                val indexId = assetIndexObj.optString("id").ifBlank { return@runCatching }
                val indexUrl = assetIndexObj.optString("url")
                val indexSha1 = assetIndexObj.optString("sha1").ifBlank { null }
                val indexFile = File(LauncherPaths.assetsDir, "indexes/$indexId.json")
                if (!indexFile.isFile || indexFile.length() <= 0L) {
                    if (indexUrl.isBlank()) error("缺少资产索引下载地址: $indexId")
                    emit(versionId, GameInstallPhase.ASSETS, "正在补全资产索引 $indexId…")
                    downloadVerified(
                        rawUrl = indexUrl,
                        destination = indexFile,
                        sha1 = indexSha1,
                        versionId = versionId,
                        phase = GameInstallPhase.ASSETS,
                        message = "补全资产索引 $indexId"
                    )
                }
                val objects = GameJsonParser.parseAssetIndex(indexFile.readText())
                // 只检查存在；完整校验会拖慢启动。
                val missing = objects.filter { obj ->
                    val file = File(LauncherPaths.assetsDir, "objects/${obj.hashPath}")
                    !file.isFile || file.length() <= 0L
                }
                if (missing.isNotEmpty()) {
                    emit(
                        versionId,
                        GameInstallPhase.ASSETS,
                        "正在补全缺失资源 ${missing.size} 个…",
                        0,
                        missing.size
                    )
                    val provider = DownloadProviders.current()
                    downloadAll(
                        items = missing.map { obj ->
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
                // pre-1.6 / legacy: objects → virtual/<id>/ (+ version resources/)
                reconstructLegacyAssets(versionId, indexId, indexFile)
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                emit(versionId, GameInstallPhase.FAILED, error.message ?: "资源补全失败")
            }.map { }
        }
    }

    private fun missingAssetObjects(versionId: String): List<AssetObject>? {
        val jsonFile = File(LauncherPaths.versionsDir, "$versionId/$versionId.json")
        if (!jsonFile.isFile) return null
        return runCatching {
            val root = JSONObject(jsonFile.readText())
            if (root.optString("inheritsFrom").isNotBlank()) return@runCatching emptyList()
            val assetIndexObj = root.optJSONObject("assetIndex") ?: return@runCatching emptyList()
            val indexId = assetIndexObj.optString("id").ifBlank { return@runCatching emptyList() }
            val indexFile = File(LauncherPaths.assetsDir, "indexes/$indexId.json")
            if (!indexFile.isFile) {
                return@runCatching listOf(AssetObject(name = "index", hash = "missing-index", size = -1L))
            }
            GameJsonParser.parseAssetIndex(indexFile.readText()).filter { obj ->
                val file = File(LauncherPaths.assetsDir, "objects/${obj.hashPath}")
                !file.isFile || file.length() <= 0L
            }
        }.getOrNull()
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
                    reconstructLegacyAssets(versionId, assetIndex.id, indexFile)
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
        val lastEmitAt = AtomicLong(0L)
        // Assets: existence+size is enough; full SHA on every file throttles throughput hard.
        val skipShaIfPresent = phase == GameInstallPhase.ASSETS
        items.map { item ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val dest = item.destination
                    val alreadyThere = dest.isFile && dest.length() > 0L &&
                        (skipShaIfPresent || Digests.matchesSha1(dest, item.sha1))
                    if (!alreadyThere) {
                        downloadVerified(
                            rawUrl = item.rawUrl,
                            destination = dest,
                            sha1 = item.sha1,
                            versionId = versionId,
                            phase = phase,
                            message = "下载 ${item.label}",
                            urlCandidates = item.urlCandidates
                        )
                    }
                    val done = completed.incrementAndGet()
                    val now = System.currentTimeMillis()
                    val prev = lastEmitAt.get()
                    if (done == 1 || done == total || now - prev >= ASSET_PROGRESS_EMIT_MS) {
                        if (lastEmitAt.compareAndSet(prev, now) || done == total || done == 1) {
                            lastEmitAt.set(now)
                            emit(versionId, phase, "已完成 $done / $total", done, total)
                        }
                    }
                }
            }
        }.awaitAll()
        emit(versionId, phase, "已完成 $total / $total", total, total)
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
        if (candidates.isEmpty()) error("缺少下载地址: $rawUrl")

        // 客户端大 jar 可多分片；库/资源不开。
        val accelerate = phase == GameInstallPhase.CLIENT

        // 整表候选源；非末位短超时。
        var remaining = candidates
        var lastError: Throwable? = null
        while (remaining.isNotEmpty()) {
            val result = downloader.download(
                remaining,
                destination,
                onProgress = { downloaded, total ->
                    _progress.value = GameInstallProgress(
                        versionId = versionId,
                        phase = phase,
                        message = message,
                        downloadedBytes = downloaded,
                        totalBytes = total
                    )
                },
                accelerate = accelerate
            )
            if (result.isFailure) {
                throw result.exceptionOrNull() ?: IOException("下载失败: $rawUrl")
            }
            if (Digests.matchesSha1(destination, sha1)) return@withContext
            destination.delete()
            lastError = IllegalStateException("SHA-1 校验失败: ${destination.name}")
            remaining = remaining.drop(1)
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

    /**
     * HMCL reconstructAssets: for virtual / map_to_resources indexes (pre-1.6, legacy),
     * copy hashed objects into assets/virtual/<id>/ and optionally <version>/resources/
     * so Beta/early-release clients find icons & sounds without the dead S3 host.
     */
    private fun reconstructLegacyAssets(versionId: String, indexId: String, indexFile: File) {
        if (!indexFile.isFile) return
        val index = runCatching { GameJsonParser.parseAssetIndexFull(indexFile.readText()) }
            .getOrNull() ?: return
        // Always rebuild virtual tree when index is marked virtual OR known legacy ids,
        // or when map_to_resources is set (pre-1.6 sounds/icons).
        val knownLegacy = indexId.equals("pre-1.6", true) ||
            indexId.equals("legacy", true)
        if (!index.virtual && !index.mapToResources && !knownLegacy) return

        emit(versionId, GameInstallPhase.ASSETS, "正在重建旧版资源目录（virtual/$indexId）…")
        val virtualRoot = File(LauncherPaths.assetsDir, "virtual/$indexId")
        val resourcesRoot = File(LauncherPaths.versionsDir, "$versionId/resources")
        var copied = 0
        var present = 0
        for (obj in index.objects) {
            if (obj.name.isBlank()) continue
            val original = File(LauncherPaths.assetsDir, "objects/${obj.hashPath}")
            if (!original.isFile || original.length() <= 0L) continue
            present++
            val virtualTarget = File(virtualRoot, obj.name)
            if (linkOrCopy(original, virtualTarget)) copied++
            if (index.mapToResources || knownLegacy) {
                linkOrCopy(original, File(resourcesRoot, obj.name))
            }
        }
        // HMCL: if fewer than 10% of objects exist, virtual tree is useless.
        if (index.objects.isNotEmpty() && present * 10 < index.objects.size) {
            android.util.Log.w(
                "VanillaInstaller",
                "legacy virtual assets sparse present=$present/${index.objects.size} for $indexId"
            )
        } else {
            android.util.Log.i(
                "VanillaInstaller",
                "legacy virtual assets index=$indexId present=$present linkedOrCopied=$copied " +
                    "mapToResources=${index.mapToResources || knownLegacy}"
            )
        }
    }

    private fun linkOrCopy(source: File, dest: File): Boolean {
        if (dest.isFile && dest.length() == source.length()) return false
        dest.parentFile?.mkdirs()
        // Prefer hardlink (same filesystem, cheap); fall back to copy.
        val linked = runCatching {
            if (dest.exists()) dest.delete()
            java.nio.file.Files.createLink(dest.toPath(), source.toPath())
            true
        }.getOrDefault(false)
        if (linked) return true
        return runCatching {
            source.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
    }

    private data class DownloadItem(
        val rawUrl: String,
        val destination: File,
        val sha1: String?,
        val label: String,
        val urlCandidates: List<String>? = null
    )

    companion object {
        private const val LIBRARY_CONCURRENCY = 12
        /** Many small asset objects — higher concurrency materially cuts wall time. */
        private const val ASSET_CONCURRENCY = 32
        /** Avoid flooding UI/StateFlow on every tiny asset completion. */
        private const val ASSET_PROGRESS_EMIT_MS = 120L
    }
}
