package com.booxin.launcher.core.multiplayer

import android.content.Context
import android.util.Log
import com.booxin.launcher.AppContainer
import com.booxin.launcher.core.BooxinGameRuntime
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.modloader.ForgeVersionClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-tap official server: ensure vanilla MC (or Forge if configured), list server, launch.
 * Mod sync from guanfu.txt has been removed.
 */
class OfficialServerJoinService {

    data class ReadyTarget(
        val versionId: String,
        val server: OfficialServerInfo
    )

    /**
     * @param onProgress percent 0–100 (or -1 indeterminate), human-readable [message]
     */
    suspend fun prepare(
        context: Context,
        server: OfficialServerInfo,
        onProgress: (percent: Int, message: String) -> Unit
    ): Result<ReadyTarget> = withContext(Dispatchers.IO) {
        runCatching {
            require(server.version.isNotBlank()) { "官服配置缺少 version" }

            onProgress(2, "正在检查本地版本…")
            AppContainer.repository.refreshVersions()
            AppContainer.repository.refreshInstalledVersions()

            val versionId = ensureVersionInstalled(server, onProgress)
            ensureServerListed(server, versionId, onProgress)
            AppContainer.repository.selectVersion(versionId)
            onProgress(100, "环境就绪，准备启动")
            ReadyTarget(versionId = versionId, server = server)
        }.onFailure { error ->
            Log.e(TAG, "prepare failed", error)
        }
    }

    private suspend fun ensureVersionInstalled(
        server: OfficialServerInfo,
        onProgress: (percent: Int, message: String) -> Unit
    ): String {
        val existing = findInstalledTarget(server)
        if (existing != null) {
            onProgress(55, "已找到本地环境 $existing")
            return existing
        }

        val forge = server.forgeVersion.trim()
        if (forge.isEmpty()) {
            onProgress(5, "正在安装原版 ${server.version}…")
            AppContainer.repository.installVersion(server.version).getOrElse { error ->
                throw IllegalStateException(
                    "原版安装失败：${error.message ?: error.javaClass.simpleName}",
                    error
                )
            }
            onProgress(80, "版本安装完成：${server.version}")
            return server.version
        }

        onProgress(5, "正在安装 ${server.version} + Forge $forge…（耗时可能较长）")
        val remote = AppContainer.repository.remoteVersions.value
            .firstOrNull { it.id.equals(server.version, ignoreCase = true) }
        val runtime = AppContainer.gameRuntime as BooxinGameRuntime
        val installedId = runtime.prepareForge(
            mcVersion = server.version,
            loaderVersion = forge,
            versionJsonUrl = remote?.url
        ).getOrElse { error ->
            throw IllegalStateException(
                "Forge 安装失败：${error.message ?: error.javaClass.simpleName}",
                error
            )
        }
        onProgress(80, "版本安装完成：$installedId")
        return installedId
    }

    private fun findInstalledTarget(server: OfficialServerInfo): String? {
        val forge = server.forgeVersion.trim()
        val expected = if (forge.isNotEmpty()) {
            ForgeVersionClient.forgeVersionId(server.version, forge)
        } else {
            server.version
        }
        val installed = AppContainer.repository.installedVersions.value
        installed.firstOrNull { it.id.equals(expected, ignoreCase = true) }?.id?.let { return it }

        if (forge.isEmpty()) {
            return installed.firstOrNull { it.id.equals(server.version, ignoreCase = true) }?.id
        }

        return installed.firstOrNull { ver ->
            val id = ver.id
            id.contains(server.version, ignoreCase = true) &&
                id.contains("forge", ignoreCase = true) &&
                (id.contains(forge, ignoreCase = true) ||
                    forge.contains(id.substringAfterLast('-'), ignoreCase = true))
        }?.id
    }

    private fun ensureServerListed(
        server: OfficialServerInfo,
        versionId: String,
        onProgress: (percent: Int, message: String) -> Unit
    ) {
        val gameDir = File(LauncherPaths.versionsDir, versionId)
        val ok = runCatching {
            MinecraftServerList.ensureListed(
                gameDir = gameDir,
                serverName = server.name.ifBlank { "Booxin 官方服务器" },
                serverAddress = server.serverAddress,
                pinToTop = true
            )
        }.getOrDefault(false)
        if (ok) {
            onProgress(99, "已写入游戏内服务器列表")
        } else {
            onProgress(99, "服务器列表写入跳过（仍可通过直连进服）")
        }
    }

    companion object {
        private const val TAG = "OfficialServerJoin"
    }
}
