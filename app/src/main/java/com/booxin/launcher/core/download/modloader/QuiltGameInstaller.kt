package com.booxin.launcher.core.download.modloader

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.GameInstallPhase
import com.booxin.launcher.core.download.game.GameInstallProgress
import com.booxin.launcher.core.download.game.LibraryDownloadHelper
import com.booxin.launcher.core.download.game.VanillaGameInstaller
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Quilt install via quilt-meta profile JSON (same shape as Fabric) — no installer.jar.
 */
class QuiltGameInstaller(
    private val vanillaInstaller: VanillaGameInstaller = VanillaGameInstaller(),
    private val libraryDownloader: LibraryDownloadHelper = LibraryDownloadHelper(),
    private val quiltClient: QuiltVersionClient = QuiltVersionClient()
) {
    private companion object {
        const val OVERALL_TOTAL = 100
        const val BASE_WEIGHT = 40
        const val PROFILE_WEIGHT = 10
        const val LIBRARY_BASE = 50
        const val LIBRARY_WEIGHT = 50
    }

    private val mutex = Mutex()
    private val _progress = MutableStateFlow<GameInstallProgress?>(null)
    val progress: StateFlow<GameInstallProgress?> = _progress.asStateFlow()

    fun isInstalled(versionId: String): Boolean {
        VersionJsonMerger.versionJsonFile(versionId) ?: return false
        val merged = VersionJsonMerger.merge(versionId) ?: return false
        if (merged.optString("mainClass").isBlank()) return false
        val clientJar = VersionJsonMerger.resolveClientJar(versionId)
        if (clientJar == null || !clientJar.isFile) return false
        val parentId = VersionJsonMerger.resolveMinecraftVersionId(versionId)
        return vanillaInstaller.isInstalled(parentId)
    }

    suspend fun install(
        mcVersion: String,
        loaderVersion: String,
        versionJsonUrl: String? = null
    ): Result<String> = mutex.withLock {
        val versionId = QuiltVersionClient.quiltVersionId(mcVersion, loaderVersion)
        runCatching {
            emitPercent(versionId, GameInstallPhase.MANIFEST, "正在安装基础版本 $mcVersion…", 0)
            coroutineScope {
                val vanillaProgressJob = launch {
                    vanillaInstaller.progress.collect { progress ->
                        if (progress == null || progress.versionId != mcVersion) return@collect
                        if (progress.phase == GameInstallPhase.DONE ||
                            progress.phase == GameInstallPhase.FAILED
                        ) {
                            return@collect
                        }
                        val fraction = progress.fraction
                        val percent = if (fraction >= 0f) {
                            (fraction * BASE_WEIGHT).toInt().coerceIn(0, BASE_WEIGHT)
                        } else {
                            0
                        }
                        emitPercent(
                            versionId,
                            GameInstallPhase.MANIFEST,
                            "基础版本：${progress.message}",
                            percent
                        )
                    }
                }
                vanillaInstaller.install(mcVersion, versionJsonUrl).getOrThrow()
                vanillaProgressJob.cancel()
            }

            emitPercent(
                versionId,
                GameInstallPhase.MODLOADER,
                "正在下载 Quilt 配置文件…",
                BASE_WEIGHT
            )
            val profileText = downloadProfile(mcVersion, loaderVersion)
            writeProfile(versionId, mcVersion, profileText)
            emitPercent(
                versionId,
                GameInstallPhase.MODLOADER,
                "Quilt 配置写入完成",
                BASE_WEIGHT + PROFILE_WEIGHT
            )

            emitPercent(versionId, GameInstallPhase.LIBRARIES, "正在下载 Quilt 依赖库…", LIBRARY_BASE)
            libraryDownloader.downloadVersionLibraries(versionId) { done, total, _ ->
                val percent = if (total > 0) {
                    LIBRARY_BASE + (done * LIBRARY_WEIGHT / total)
                } else {
                    LIBRARY_BASE
                }
                emitPercent(
                    versionId,
                    GameInstallPhase.LIBRARIES,
                    "下载 Quilt 依赖 $done / $total",
                    percent
                )
            }

            emitPercent(versionId, GameInstallPhase.DONE, "Quilt 安装完成", OVERALL_TOTAL)
            versionId
        }.onFailure { error ->
            emit(
                versionId,
                GameInstallPhase.FAILED,
                error.message ?: "Quilt 安装失败"
            )
        }
    }

    private suspend fun downloadProfile(mcVersion: String, loaderVersion: String): String =
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            for (url in quiltClient.profileUrls(mcVersion, loaderVersion)) {
                val result = runCatching { executeGet(url) }
                if (result.isSuccess) {
                    val text = result.getOrThrow()
                    if (text.contains("inheritsFrom") || text.contains("mainClass")) {
                        return@withContext text
                    }
                    lastError = IllegalStateException("Quilt profile 无效: $url")
                } else {
                    lastError = result.exceptionOrNull()
                }
            }
            throw lastError ?: IllegalStateException("Quilt 配置下载失败")
        }

    private fun writeProfile(versionId: String, mcVersion: String, profileText: String) {
        val profile = JSONObject(profileText)
        profile.put("id", versionId)
        if (profile.optString("inheritsFrom").isBlank()) {
            profile.put("inheritsFrom", mcVersion)
        }
        val dir = File(LauncherPaths.versionsDir, versionId).also { it.mkdirs() }
        File(dir, "$versionId.json").writeText(profile.toString(2))
    }

    private fun executeGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.USER_AGENT)
            .get()
            .build()
        HttpClients.shared.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: $url")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun emitPercent(
        versionId: String,
        phase: GameInstallPhase,
        message: String,
        percent: Int
    ) {
        val clamped = percent.coerceIn(0, OVERALL_TOTAL)
        _progress.value = GameInstallProgress(
            versionId = versionId,
            phase = phase,
            message = message,
            completed = clamped,
            total = OVERALL_TOTAL
        )
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
}
