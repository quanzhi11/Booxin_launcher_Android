package com.booxin.launcher.core.multiplayer

import android.content.Context
import android.util.Log
import com.booxin.launcher.AppContainer
import com.booxin.launcher.core.BooxinGameRuntime
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.modloader.ForgeVersionClient
import com.booxin.launcher.core.net.FileDownloader
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One-tap official server: ensure MC+Forge, sync guanfu mods, return launch target.
 */
class OfficialServerJoinService(
    private val downloader: FileDownloader = FileDownloader()
) {

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

            val versionId = ensureForgeInstalled(server, onProgress)
            ensureMods(context, server, versionId, onProgress)
            ensureServerListed(server, versionId, onProgress)
            AppContainer.repository.selectVersion(versionId)
            onProgress(100, "环境就绪，准备启动")
            ReadyTarget(versionId = versionId, server = server)
        }.onFailure { error ->
            Log.e(TAG, "prepare failed", error)
        }
    }

    private suspend fun ensureForgeInstalled(
        server: OfficialServerInfo,
        onProgress: (percent: Int, message: String) -> Unit
    ): String {
        val existing = findInstalledTarget(server)
        if (existing != null) {
            onProgress(55, "已找到本地环境 $existing")
            return existing
        }

        val forge = server.forgeVersion.trim()
        require(forge.isNotEmpty()) {
            "官服需要 Forge，但 guanfu.txt 未配置 mod_forge"
        }

        onProgress(5, "正在安装 ${server.version} + Forge $forge…（耗时可能较长）")
        val remote = AppContainer.repository.remoteVersions.value
            .firstOrNull { it.id.equals(server.version, ignoreCase = true) }
        val runtime = AppContainer.gameRuntime as BooxinGameRuntime
        // ForgeGameInstaller emits detailed progress via repository.forgeInstallProgress;
        // UI should collect that. Keep a coarse status here as fallback.
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

    private suspend fun ensureMods(
        context: Context,
        server: OfficialServerInfo,
        versionId: String,
        onProgress: (percent: Int, message: String) -> Unit
    ) {
        if (server.modUrls.isEmpty()) return
        val modsDir = File(LauncherPaths.versionsDir, "$versionId/mods").also { it.mkdirs() }
        val cache = ModDownloadCache(context)
        val total = server.modUrls.size

        server.modUrls.forEachIndexed { index, url ->
            val fileName = resolveModFileName(url)
            val dest = File(modsDir, fileName)
            val outdated = cache.isOlderThanServer(fileName, server.modsUpdatedAt)
            val needs = !dest.isFile || dest.length() < 64L || outdated
            val base = 80 + (index * 15 / total.coerceAtLeast(1))
            if (!needs) {
                onProgress(base + 5, "模组已就绪：$fileName")
                return@forEachIndexed
            }
            if (dest.exists()) {
                onProgress(base, "更新官服模组：$fileName")
                dest.delete()
            } else {
                onProgress(base, "下载官服模组：$fileName")
            }
            // guanfu.txt uses http://; Android blocks cleartext unless allowlisted.
            // 支持 https 时优先。
            val downloadUrl = preferHttps(url)
            val progressCb: (Long, Long) -> Unit = { downloaded, totalBytes ->
                if (totalBytes > 0L) {
                    val frac = (downloaded.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
                    val pct = base + (frac * (15.0 / total.coerceAtLeast(1))).toInt()
                    onProgress(
                        pct.coerceIn(80, 98),
                        "下载模组 $fileName ${(frac * 100).toInt()}%"
                    )
                }
            }
            val result = downloader.download(downloadUrl, dest, progressCb).recoverCatching { first ->
                if (downloadUrl == url) throw first
                downloader.download(url, dest, progressCb).getOrThrow()
            }
            result.getOrElse { error ->
                throw IllegalStateException(
                    "模组下载失败 $fileName：${error.message ?: error.javaClass.simpleName}",
                    error
                )
            }
            if (!dest.isFile || dest.length() < 64L) {
                error("模组下载失败或文件过小：$fileName")
            }
            cache.setDownloadedAt(fileName, Instant.now())
            onProgress(
                (80 + ((index + 1) * 15 / total.coerceAtLeast(1))).coerceAtMost(98),
                "模组已安装：$fileName"
            )
        }
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

    private fun resolveModFileName(url: String): String {
        return try {
            val path = java.net.URI(url).path
            File(path).name.takeIf { it.isNotBlank() }
                ?: "official-mod-${System.currentTimeMillis()}.jar"
        } catch (_: Exception) {
            "official-mod-${System.currentTimeMillis()}.jar"
        }
    }

    private fun preferHttps(url: String): String {
        if (!url.startsWith("http://", ignoreCase = true)) return url
        return "https://" + url.substring("http://".length)
    }

    private class ModDownloadCache(context: Context) {
        private val file = File(context.filesDir, "official-mod-downloads.json")

        fun getDownloadedAt(modFileName: String): Instant? {
            val map = load()
            val raw = map.optLong(normalize(modFileName), -1L)
            return if (raw > 0) Instant.ofEpochMilli(raw) else null
        }

        fun setDownloadedAt(modFileName: String, at: Instant) {
            val map = load()
            map.put(normalize(modFileName), at.toEpochMilli())
            file.writeText(map.toString())
        }

        fun isOlderThanServer(modFileName: String, serverUpdatedAt: LocalDate?): Boolean {
            if (serverUpdatedAt == null) return false
            val local = getDownloadedAt(modFileName) ?: return true
            val localDay = local.atZone(ZoneId.systemDefault()).toLocalDate()
            return localDay.isBefore(serverUpdatedAt)
        }

        private fun load(): JSONObject {
            if (!file.isFile) return JSONObject()
            return runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        }

        private fun normalize(name: String) = File(name).name.trim().lowercase()
    }

    companion object {
        private const val TAG = "OfficialServerJoin"
    }
}
