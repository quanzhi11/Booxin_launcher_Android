package com.booxin.launcher.core.download.modloader

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.GameInstallPhase
import com.booxin.launcher.core.download.game.GameInstallProgress
import com.booxin.launcher.core.download.game.LibraryDownloadHelper
import com.booxin.launcher.core.download.game.VanillaGameInstaller
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.java.EmbeddedJavaRunner
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
import org.json.JSONObject
import java.io.File

/**
 * OptiFine-only install: vanilla → download OptiFine jar → silent `optifine.Installer`
 * in a temp `.minecraft`, then normalize version id to `{mc}-optifine-{type}_{patch}`.
 */
class OptiFineGameInstaller(
    private val vanillaInstaller: VanillaGameInstaller = VanillaGameInstaller(),
    private val libraryDownloader: LibraryDownloadHelper = LibraryDownloadHelper(),
    private val optiFineClient: OptiFineVersionClient = OptiFineVersionClient(),
    private val fileDownloader: FileDownloader = FileDownloader()
) {
    private companion object {
        const val OVERALL_TOTAL = 100
        const val BASE_WEIGHT = 35
        const val DOWNLOAD_WEIGHT = 15
        const val INSTALLER_BASE = 50
        const val INSTALLER_WEIGHT = 30
        const val LIBRARY_BASE = 80
        const val LIBRARY_WEIGHT = 20
    }

    private val mutex = Mutex()
    private val _progress = MutableStateFlow<GameInstallProgress?>(null)
    val progress: StateFlow<GameInstallProgress?> = _progress.asStateFlow()

    fun isInstalled(versionId: String): Boolean {
        if (!versionId.contains('-') && !versionId.contains('_')) return false
        VersionJsonMerger.resolveInheritsFrom(versionId) ?: return false
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
        type: String,
        patch: String,
        versionJsonUrl: String? = null,
        java: InstalledJavaRuntime
    ): Result<String> = mutex.withLock {
        val versionId = OptiFineVersionClient.optiFineVersionId(mcVersion, type, patch)
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
                "正在下载 OptiFine…",
                BASE_WEIGHT
            )
            val installerJar = downloadInstaller(mcVersion, type, patch)
            emitPercent(
                versionId,
                GameInstallPhase.MODLOADER,
                "OptiFine 下载完成，正在静默安装…",
                BASE_WEIGHT + DOWNLOAD_WEIGHT
            )

            runInstaller(mcVersion, versionId, installerJar, java)
            emitPercent(
                versionId,
                GameInstallPhase.MODLOADER,
                "OptiFine 安装器完成",
                INSTALLER_BASE + INSTALLER_WEIGHT
            )

            emitPercent(versionId, GameInstallPhase.LIBRARIES, "正在下载 OptiFine 依赖库…", LIBRARY_BASE)
            libraryDownloader.downloadVersionLibraries(versionId) { done, total, _ ->
                val percent = if (total > 0) {
                    LIBRARY_BASE + (done * LIBRARY_WEIGHT / total)
                } else {
                    LIBRARY_BASE
                }
                emitPercent(
                    versionId,
                    GameInstallPhase.LIBRARIES,
                    "下载 OptiFine 依赖 $done / $total",
                    percent
                )
            }

            emitPercent(versionId, GameInstallPhase.DONE, "OptiFine 安装完成", OVERALL_TOTAL)
            versionId
        }.onFailure { error ->
            emit(
                versionId,
                GameInstallPhase.FAILED,
                error.message ?: "OptiFine 安装失败"
            )
        }
    }

    private suspend fun downloadInstaller(mcVersion: String, type: String, patch: String): File =
        withContext(Dispatchers.IO) {
            val cacheDir = File(LauncherPaths.rootDir, "cache/optifine").also { it.mkdirs() }
            val destination = File(cacheDir, "OptiFine_${mcVersion}_${type}_$patch.jar")
            if (destination.isFile && destination.length() > 1024L) return@withContext destination

            var lastError: Throwable? = null
            for (url in optiFineClient.downloadUrls(mcVersion, type, patch)) {
                val result = fileDownloader.download(url, destination)
                if (result.isSuccess && destination.isFile && destination.length() > 1024L) {
                    return@withContext destination
                }
                lastError = result.exceptionOrNull()
            }
            throw lastError ?: IllegalStateException("OptiFine 下载失败")
        }

    private suspend fun runInstaller(
        mcVersion: String,
        versionId: String,
        installerJar: File,
        java: InstalledJavaRuntime
    ) = withContext(Dispatchers.IO) {
        val tempRoot = File(
            LauncherPaths.rootDir,
            "cache/optifine/install-${System.currentTimeMillis()}"
        )
        val tempMc = File(tempRoot, ".minecraft")
        val tempVersionDir = File(tempMc, "versions/$mcVersion")
        try {
            tempVersionDir.mkdirs()
            writeLauncherProfiles(tempMc, mcVersion)

            val sourceDir = File(LauncherPaths.versionsDir, mcVersion)
            val sourceJson = File(sourceDir, "$mcVersion.json")
            val sourceJar = File(sourceDir, "$mcVersion.jar")
            require(sourceJson.isFile && sourceJar.isFile) {
                "缺少原版文件: $mcVersion"
            }
            File(tempVersionDir, "$mcVersion.json").writeText(stripBom(sourceJson.readText()))
            sourceJar.copyTo(File(tempVersionDir, "$mcVersion.jar"), overwrite = true)

            emitPercent(versionId, GameInstallPhase.MODLOADER, "正在运行 OptiFine 安装器…", INSTALLER_BASE)
            val exit = EmbeddedJavaRunner.run(
                java = java,
                workingDir = tempRoot,
                command = listOf("-cp", installerJar.absolutePath, "optifine.Installer"),
                extraJvmArgs = listOf("-Duser.home=${tempRoot.absolutePath}")
            )
            if (exit != 0) {
                error("OptiFine 安装器退出码 $exit")
            }

            val installedDir = findInstalledOptiFineDir(tempMc, mcVersion)
                ?: error("OptiFine 安装器未生成版本目录")
            normalizeAndCopy(installedDir, versionId, mcVersion)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun findInstalledOptiFineDir(tempMc: File, mcVersion: String): File? {
        val versions = File(tempMc, "versions")
        if (!versions.isDirectory) return null
        return versions.listFiles()
            ?.filter { it.isDirectory }
            ?.filter { dir ->
                val name = dir.name
                name.startsWith("$mcVersion-OptiFine", ignoreCase = true) ||
                    name.startsWith("$mcVersion-optifine", ignoreCase = true)
            }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun normalizeAndCopy(sourceDir: File, versionId: String, mcVersion: String) {
        val sourceJson = File(sourceDir, "${sourceDir.name}.json")
        require(sourceJson.isFile) { "缺少 OptiFine version.json" }
        val targetDir = File(LauncherPaths.versionsDir, versionId).also { it.mkdirs() }
        val targetJson = File(targetDir, "$versionId.json")
        val text = stripBom(sourceJson.readText())
            .replace(sourceDir.name, versionId)
        val json = JSONObject(text)
        json.put("id", versionId)
        if (json.optString("inheritsFrom").isBlank()) {
            json.put("inheritsFrom", mcVersion)
        }
        targetJson.writeText(json.toString(2))

        // Copy any sibling jar the installer may have written under versions/.
        sourceDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            ?.forEach { jar ->
                val name = if (jar.nameWithoutExtension.equals(sourceDir.name, ignoreCase = true)) {
                    "$versionId.jar"
                } else {
                    jar.name
                }
                jar.copyTo(File(targetDir, name), overwrite = true)
            }

        // Libraries written under temp .minecraft/libraries
        val tempLibs = File(sourceDir.parentFile?.parentFile, "libraries")
        if (tempLibs.isDirectory) {
            tempLibs.copyRecursively(LauncherPaths.librariesDir, overwrite = false)
        }
    }

    private fun writeLauncherProfiles(minecraftDir: File, mcVersion: String) {
        val profiles = JSONObject()
            .put(
                "profiles",
                JSONObject().put(
                    "Booxin",
                    JSONObject()
                        .put("name", "Booxin")
                        .put("lastVersionId", mcVersion)
                        .put("type", "custom")
                )
            )
            .put("selectedProfile", "Booxin")
        File(minecraftDir, "launcher_profiles.json").writeText(profiles.toString(2))
    }

    private fun stripBom(text: String): String =
        if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text

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
