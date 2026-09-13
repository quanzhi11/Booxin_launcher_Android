package com.booxin.launcher.core.multiplayer

import com.booxin.launcher.AppContainer
import com.booxin.launcher.core.download.modloader.FabricVersionClient
import com.booxin.launcher.core.download.modloader.ForgeVersionClient
import com.booxin.launcher.core.download.modloader.NeoForgeVersionClient
import com.booxin.launcher.core.download.modloader.QuiltVersionClient
import com.booxin.launcher.core.version.AndroidIncompatibleMods
import com.booxin.launcher.core.version.VersionModsManager
import com.booxin.launcher.data.model.GameVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Guest-side: resolve room host deps and create a matching isolated version.
 * Aligns with PC MainWindow.BtnDownloadRoomModpack_Click (fresh install + mod sync).
 */
object RoomHostDependencyInstaller {

    data class Result(
        val versionId: String,
        val installedModCount: Int,
        val totalMods: Int,
        val reusedExactMatch: Boolean,
        val message: String
    )

    suspend fun resolveSnapshot(roomCode: String): RoomDependencySnapshot =
        withContext(Dispatchers.IO) {
            val room = BooxinRoomApi().getRoom(roomCode).getOrNull()
                ?: return@withContext RoomDependencySnapshot()
            RoomDependencySnapshot(
                modpackUrl = room.modpackUrl,
                gameVersion = room.modpackGameVersion ?: room.version,
                loader = room.modpackLoader,
                mods = room.resolveMods()
            )
        }

    suspend fun downloadAndCreate(
        snapshot: RoomDependencySnapshot,
        onProgress: (String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        if (!snapshot.hasDownloadableContent) {
            error("当前房间没有可同步的房主依赖")
        }

        val mods = snapshot.mods.filter { it.hasDownloadSource }
        val fingerprint = RoomSessionVersionService.computeFingerprint(
            snapshot.gameVersion,
            snapshot.loader,
            mods
        )
        val installed = AppContainer.repository.installedVersions.value
        val exact = RoomSessionVersionService.findExactMatch(installed, fingerprint)
        if (exact != null) {
            AppContainer.repository.selectVersion(exact.id)
            return@withContext Result(
                versionId = exact.id,
                installedModCount = VersionModsManager.list(exact.id).count { it.enabled },
                totalMods = mods.size,
                reusedExactMatch = true,
                message = "已匹配联机版本：${exact.id}"
            )
        }

        if (mods.isEmpty() && !snapshot.modpackUrl.isNullOrBlank()) {
            onProgress("正在下载房间整合包…")
            val versionId = AppContainer.communityRepository.installModpackFromUrl(
                url = snapshot.modpackUrl,
                requestedTargetVersionId = null
            ) { progress ->
                onProgress(
                    buildString {
                        append(progress.stage)
                        if (progress.detail.isNotBlank()) append(" · ").append(progress.detail)
                    }
                )
            }.getOrThrow()
            return@withContext Result(
                versionId = versionId,
                installedModCount = VersionModsManager.list(versionId).count { it.enabled },
                totalMods = 0,
                reusedExactMatch = false,
                message = "房间整合包已安装：$versionId"
            )
        }

        val pureGameVersion = RoomHostDependencyService.extractPureVersion(snapshot.gameVersion)
            .ifBlank { snapshot.gameVersion?.trim().orEmpty() }
        if (pureGameVersion.isBlank()) {
            error("房间未提供有效的游戏版本，无法安装联机版本。")
        }

        val loader = RoomHostDependencyService.normalizeLoader(snapshot.loader)
        val newName = RoomSessionVersionService.buildNewInstanceName(
            pureGameVersion,
            loader,
            fingerprint
        )

        onProgress("正在刷新官方版本列表…")
        AppContainer.repository.refreshVersions().getOrElse { err ->
            error("无法刷新版本列表：${err.message ?: "unknown"}")
        }

        onProgress("正在安装联机版本（$pureGameVersion / ${loader ?: "原版"}）…")
        val installedBaseId = installGameAndLoader(
            pureGameVersion = pureGameVersion,
            loader = loader,
            onProgress = onProgress
        )

        onProgress("正在创建联机专用实例：$newName")
        val sessionId = materializeRoomSessionInstance(
            sourceId = installedBaseId,
            targetId = newName,
            clientVersion = pureGameVersion
        )

        val modsDir = VersionModsManager.modsDir(sessionId)
        var installedFiles = emptyList<java.io.File>()
        if (mods.isNotEmpty()) {
            onProgress("正在下载房主模组（共 ${mods.size} 个）…")
            installedFiles = RoomHostDependencyService().downloadModsToDirectory(
                mods,
                modsDir
            ) { done, total, name ->
                onProgress(if (total <= 0) name else "$name（$done/$total）")
            }
        }
        AndroidIncompatibleMods.scanAndDisable(sessionId)

        RoomSessionVersionService.writeMarker(
            sessionId,
            RoomSessionProfileMarker(
                fingerprint = fingerprint,
                gameVersion = pureGameVersion,
                loader = loader,
                modCount = installedFiles.size,
                createdAtUtc = RoomSessionVersionService.utcNow()
            )
        )
        AppContainer.repository.refreshInstalledVersions()
        AppContainer.repository.selectVersion(sessionId)
        Result(
            versionId = sessionId,
            installedModCount = installedFiles.size,
            totalMods = mods.size,
            reusedExactMatch = false,
            message = "已安装联机版本：$sessionId（同步 ${installedFiles.size}/${mods.size} 个模组）"
        )
    }

    /**
     * Fresh-install vanilla (+ recommended loader), matching PC BuildRoomSessionLoaders + download.
     */
    private suspend fun installGameAndLoader(
        pureGameVersion: String,
        loader: String?,
        onProgress: (String) -> Unit
    ): String {
        val normalized = RoomHostDependencyService.normalizeLoader(loader)
        if (normalized.isNullOrBlank() || normalized == "vanilla") {
            onProgress("正在安装原版 $pureGameVersion…")
            AppContainer.repository.installVersion(pureGameVersion).getOrThrow()
            return pureGameVersion
        }

        return when (normalized) {
            "fabric" -> {
                val build = FabricVersionClient().listBuilds(pureGameVersion).getOrThrow()
                    .firstOrNull()
                    ?: error("未找到适合 $pureGameVersion 的 Fabric Loader")
                onProgress("正在安装 Fabric ${build.loaderVersion}…")
                AppContainer.repository.installFabricVersion(
                    pureGameVersion,
                    build.loaderVersion
                ).getOrThrow()
            }
            "quilt" -> {
                val build = QuiltVersionClient().listBuilds(pureGameVersion).getOrThrow()
                    .firstOrNull()
                    ?: error("未找到适合 $pureGameVersion 的 Quilt Loader")
                onProgress("正在安装 Quilt ${build.loaderVersion}…")
                AppContainer.repository.installQuiltVersion(
                    pureGameVersion,
                    build.loaderVersion
                ).getOrThrow()
            }
            "forge" -> {
                val builds = ForgeVersionClient().listBuilds(pureGameVersion).getOrThrow()
                val build = builds.firstOrNull { it.recommended } ?: builds.firstOrNull()
                    ?: error("未找到适合 $pureGameVersion 的 Forge")
                onProgress("正在安装 Forge ${build.loaderVersion}…（耗时可能较长）")
                val java = AppContainer.javaEnvironment.ensureForMinecraft(pureGameVersion).getOrThrow()
                AppContainer.repository.installForgeVersion(
                    mcVersion = pureGameVersion,
                    loaderVersion = build.loaderVersion,
                    java = java,
                    isNeoForge = false
                ).getOrThrow()
            }
            "neoforge" -> {
                val builds = NeoForgeVersionClient().listBuilds(pureGameVersion).getOrThrow()
                val build = builds.firstOrNull { it.recommended } ?: builds.firstOrNull()
                    ?: error("未找到适合 $pureGameVersion 的 NeoForge")
                onProgress("正在安装 NeoForge ${build.loaderVersion}…（耗时可能较长）")
                val java = AppContainer.javaEnvironment.ensureForMinecraft(pureGameVersion).getOrThrow()
                AppContainer.repository.installNeoForgeVersion(
                    mcVersion = pureGameVersion,
                    loaderVersion = build.loaderVersion,
                    java = java
                ).getOrThrow()
            }
            else -> error("暂不支持自动安装加载器：$normalized")
        }
    }

    /**
     * Copy installed base into an isolated room-session profile (empty mods), like PC's
     * custom instance name. Keeps the base profile for reuse.
     */
    private fun materializeRoomSessionInstance(
        sourceId: String,
        targetId: String,
        clientVersion: String
    ): String {
        if (sourceId.equals(targetId, ignoreCase = true)) {
            VersionModsManager.modsDir(targetId).mkdirs()
            return targetId
        }
        if (!RoomSessionVersionService.cloneInstanceKeepSource(
                sourceInstanceName = sourceId,
                targetInstanceName = targetId,
                clientVersion = clientVersion
            )
        ) {
            error("从「$sourceId」创建联机版本「$targetId」失败")
        }
        return targetId
    }

    fun hasDownloadableContent(snapshot: RoomDependencySnapshot): Boolean =
        snapshot.hasDownloadableContent

    fun findExactMatchVersion(
        snapshot: RoomDependencySnapshot,
        installed: List<GameVersion> = AppContainer.repository.installedVersions.value
    ): GameVersion? {
        val mods = snapshot.mods.filter { it.hasDownloadSource }
        val fingerprint = RoomSessionVersionService.computeFingerprint(
            snapshot.gameVersion,
            snapshot.loader,
            mods
        )
        RoomSessionVersionService.findExactMatch(installed, fingerprint)?.let { return it }
        // Vanilla / no published mods: reuse any matching installed instance.
        if (mods.isEmpty() && snapshot.modpackUrl.isNullOrBlank() &&
            !snapshot.gameVersion.isNullOrBlank()
        ) {
            return RoomSessionVersionService.findCloneSource(
                installed,
                snapshot.gameVersion,
                snapshot.loader
            )
        }
        return null
    }
}
