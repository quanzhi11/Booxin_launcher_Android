package com.booxin.launcher.core.download.modloader

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.GameInstallPhase
import com.booxin.launcher.core.download.game.GameInstallProgress
import com.booxin.launcher.core.download.game.LibraryDownloadHelper
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.download.game.VanillaGameInstaller
import com.booxin.launcher.core.java.InstalledJavaRuntime
import com.booxin.launcher.core.net.FileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class ForgeGameInstaller(
    private val vanillaInstaller: VanillaGameInstaller = VanillaGameInstaller(),
    private val libraryDownloader: LibraryDownloadHelper = LibraryDownloadHelper(),
    private val forgeClient: ForgeVersionClient = ForgeVersionClient(),
    private val fileDownloader: FileDownloader = FileDownloader()
) {
    private companion object {
        const val OVERALL_TOTAL = 100
        const val BASE_WEIGHT = 12
        const val INSTALLER_WEIGHT = 4
        const val PROCESSOR_BASE = 18
        const val PROCESSOR_WEIGHT = 64
        const val LIBRARY_BASE = 84
        const val LIBRARY_WEIGHT = 16
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
        versionJsonUrl: String? = null,
        java: InstalledJavaRuntime
    ): Result<String> = mutex.withLock {
        val versionId = ForgeVersionClient.forgeVersionId(mcVersion, loaderVersion)
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
                "正在下载 Forge 安装器…",
                BASE_WEIGHT
            )
            val installerJar = downloadInstaller(mcVersion, loaderVersion)
            emitPercent(
                versionId,
                GameInstallPhase.MODLOADER,
                "Forge 安装器下载完成",
                BASE_WEIGHT + INSTALLER_WEIGHT
            )

            when (ForgeInstallerRunner.detectInstallerKind(installerJar)) {
                ForgeInstallerKind.NEW_SPEC -> {
                    ForgeNewInstaller.install(
                        java = java,
                        installerJar = installerJar,
                        mcVersion = mcVersion,
                        versionId = versionId,
                        libraryDownloader = libraryDownloader,
                        onProgress = { message, step, total ->
                            val percent = if (total > 0) {
                                PROCESSOR_BASE + (step * PROCESSOR_WEIGHT / total)
                            } else {
                                PROCESSOR_BASE
                            }
                            emitPercent(versionId, GameInstallPhase.MODLOADER, message, percent)
                        }
                    ).getOrThrow()
                }
                ForgeInstallerKind.OLD_LEGACY -> {
                    ForgeInstallerRunner.installLegacy(installerJar, versionId).getOrThrow()
                }
                ForgeInstallerKind.BOOTSTRAP_INJECTOR -> {
                    val injectorJar = downloadInjector()
                    ForgeInstallerRunner.runModernInstaller(java, installerJar, injectorJar).getOrThrow()
                    ensureForgeVersionJson(versionId, mcVersion, loaderVersion)
                }
            }

            emitPercent(versionId, GameInstallPhase.LIBRARIES, "正在下载 Forge 依赖库…", LIBRARY_BASE)
            libraryDownloader.downloadVersionLibraries(versionId) { done, total, _ ->
                val percent = if (total > 0) {
                    LIBRARY_BASE + (done * LIBRARY_WEIGHT / total)
                } else {
                    LIBRARY_BASE
                }
                emitPercent(
                    versionId,
                    GameInstallPhase.LIBRARIES,
                    "下载 Forge 依赖 $done / $total",
                    percent
                )
            }

            emitPercent(versionId, GameInstallPhase.DONE, "Forge 安装完成", OVERALL_TOTAL)
            versionId
        }.onFailure { error ->
            emit(
                versionId,
                GameInstallPhase.FAILED,
                error.message ?: "Forge 安装失败"
            )
        }
    }

    private suspend fun downloadInstaller(mcVersion: String, loaderVersion: String): File =
        withContext(Dispatchers.IO) {
            val cacheDir = File(LauncherPaths.rootDir, "cache/forge").also { it.mkdirs() }
            val destination = File(cacheDir, "installer-$mcVersion-$loaderVersion.jar")
            if (destination.isFile && destination.length() > 1024L) return@withContext destination

            var lastError: Throwable? = null
            for (url in forgeClient.installerUrls(mcVersion, loaderVersion)) {
                val result = fileDownloader.download(url, destination)
                if (result.isSuccess && destination.isFile && destination.length() > 1024L) {
                    return@withContext destination
                }
                lastError = result.exceptionOrNull()
            }
            throw lastError ?: IllegalStateException("Forge 安装器下载失败")
        }

    private suspend fun downloadInjector(): File = withContext(Dispatchers.IO) {
        val cacheDir = File(LauncherPaths.rootDir, "cache/forge").also { it.mkdirs() }
        val destination = File(cacheDir, "forge-installer-1.2.0.jar")
        if (destination.isFile && destination.length() > 1024L) return@withContext destination

        var lastError: Throwable? = null
        for (url in forgeClient.injectorJarUrls()) {
            val result = fileDownloader.download(url, destination)
            if (result.isSuccess && destination.isFile && destination.length() > 1024L) {
                return@withContext destination
            }
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("无法下载 Forge 注入器")
    }

    private fun ensureForgeVersionJson(versionId: String, mcVersion: String, loaderVersion: String) {
        if (VersionJsonMerger.versionJsonFile(versionId) != null) return
        val candidates = LauncherPaths.versionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.filter { dir ->
                val name = dir.name
                name.contains("forge", ignoreCase = true) &&
                    name.contains(mcVersion) &&
                    name.contains(loaderVersion)
            }
            .orEmpty()
        val sourceDir = candidates.maxByOrNull { it.lastModified() } ?: return
        val sourceJson = File(sourceDir, "${sourceDir.name}.json")
        if (!sourceJson.isFile) return
        val targetDir = File(LauncherPaths.versionsDir, versionId).also { it.mkdirs() }
        val targetJson = File(targetDir, "$versionId.json")
        val text = sourceJson.readText().replace(sourceDir.name, versionId)
        targetJson.writeText(text)
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
