package com.booxin.launcher.core.runtime

import android.os.Build
import com.booxin.launcher.BooxinApp
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.launch.GlRendererKind
import com.booxin.launcher.core.net.FileDownloader
import com.booxin.launcher.core.plugin.PluginInstaller
import com.booxin.launcher.core.plugin.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Downloads third-party renderer plugin APKs and extracts native libs for the device ABI.
 * Delegates new installs to [PluginInstaller] while keeping legacy `renderers/` paths.
 */
object RendererInstaller {
    private val downloader = FileDownloader()

    fun pluginsRoot(): File = File(LauncherPaths.runtimeDir, "renderers").also { it.mkdirs() }

    fun installDir(pkg: RendererPackage): File = File(pluginsRoot(), pkg.id)

    fun isInstalled(kind: GlRendererKind): Boolean {
        if (!kind.requiresPlugin) return true
        PluginManager.findByKind(kind)?.let { return it.installed && it.enabled }
        val pkg = RendererPackages.forKind(kind) ?: return false
        return isInstalled(pkg)
    }

    fun isInstalled(pkg: RendererPackage): Boolean {
        val pluginDir = PluginInstaller.installDir(pkg.id)
        if (PluginInstaller.isReady(pluginDir, pkg.glLib)) return true
        val dir = installDir(pkg)
        val marker = File(dir, ".ready")
        val gl = File(dir, pkg.glLib)
        return marker.isFile && gl.isFile && gl.length() > 0L
    }

    fun glLibrary(kind: GlRendererKind): File? {
        PluginManager.findByKind(kind)?.let { p ->
            if (!p.enabled) return null
            val dir = p.nativeDir ?: return null
            return File(dir, p.glLib).takeIf { it.isFile }
        }
        val pkg = RendererPackages.forKind(kind) ?: return null
        val pluginGl = File(PluginInstaller.installDir(pkg.id), pkg.glLib)
        if (pluginGl.isFile) return pluginGl
        return File(installDir(pkg), pkg.glLib).takeIf { it.isFile }
    }

    fun pluginNativeDir(kind: GlRendererKind): File? {
        PluginManager.enabledRendererNativeDir(kind)?.let { return it }
        val pkg = RendererPackages.forKind(kind) ?: return null
        val pluginDir = PluginInstaller.installDir(pkg.id)
        if (PluginInstaller.isReady(pluginDir, pkg.glLib)) return pluginDir
        return installDir(pkg).takeIf { isInstalled(pkg) }
    }

    suspend fun ensureInstalled(kind: GlRendererKind): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (!kind.requiresPlugin) {
                return@runCatching File(LauncherPaths.runtimeDir, "natives")
            }
            if (kind == GlRendererKind.REL) {
                refreshRelFromSideloadIfPresent()
            }
            PluginManager.findByKind(kind)?.let { p ->
                if (p.installed && !p.enabled) {
                    error("插件已禁用: ${p.name}（请在设置 → 插件管理中启用）")
                }
            }
            PluginManager.enabledRendererNativeDir(kind)?.let { return@runCatching it }
            val pkg = requireNotNull(RendererPackages.forKind(kind)) {
                "未知渲染器: ${kind.displayName}"
            }
            if (isInstalled(pkg)) {
                return@runCatching PluginInstaller.installDir(pkg.id).takeIf {
                    PluginInstaller.isReady(it, pkg.glLib)
                } ?: installDir(pkg)
            }
            val ctx = runCatching { BooxinApp.getAppContext() }.getOrNull()
            if (ctx != null) {
                PluginManager.installBuiltinPackage(ctx, pkg).nativeDir
                    ?: installDir(pkg)
            } else {
                install(pkg)
            }
        }
    }

    /**
     * Re-extract REL from a sideloaded APK (adb push) so device natives match
     * a known-good build. Checked paths:
     * - app external files: RELv1.0.0.apk
     * - Download/RELv1.0.0.apk
     * - game-root cache/renderers/rel.apk
     */
    fun refreshRelFromSideloadIfPresent(): Boolean {
        val ctx = runCatching { BooxinApp.getAppContext() }.getOrNull()
        val candidates = buildList {
            ctx?.getExternalFilesDir(null)?.let { add(File(it, "RELv1.0.0.apk")) }
            add(File("/sdcard/Download/RELv1.0.0.apk"))
            if (LauncherPaths.isInitialized) {
                add(File(LauncherPaths.rootDir, "cache/renderers/rel.apk"))
            }
        }
        val apk = candidates.firstOrNull { it.isFile && it.length() > 1_000_000L } ?: return false
        return runCatching {
            kotlinx.coroutines.runBlocking {
                PluginInstaller.installFromApkFile(
                    apk,
                    preferredId = "rel",
                    preferredName = "REL"
                )
            }
            val consumed = File(apk.parentFile, "${apk.name}.installed")
            if (!apk.renameTo(consumed)) {
                apk.copyTo(consumed, overwrite = true)
                apk.delete()
            }
            android.util.Log.i(
                "RendererInstaller",
                "REFRESHed REL from sideload ${consumed.absolutePath} size=${consumed.length()}"
            )
            true
        }.getOrElse { t ->
            android.util.Log.w("RendererInstaller", "REL sideload refresh failed: ${t.message}")
            false
        }
    }

    suspend fun install(pkg: RendererPackage): File = withContext(Dispatchers.IO) {
        val ctx = runCatching { BooxinApp.getAppContext() }.getOrNull()
        if (ctx != null) {
            return@withContext PluginInstaller.installBuiltin(pkg)
        }
        // Fallback without Application context (tests).
        val cacheDir = File(LauncherPaths.rootDir, "cache/renderers").also { it.mkdirs() }
        val apk = File(cacheDir, "${pkg.id}.apk")
        var lastError: Throwable? = null
        for (url in downloadCandidates(pkg.downloadUrl)) {
            val result = downloader.download(url, apk)
            if (result.isSuccess && apk.isFile && apk.length() > 0L) {
                lastError = null
                break
            }
            lastError = result.exceptionOrNull()
                ?: IllegalStateException("空文件: $url")
            apk.delete()
        }
        if (lastError != null || !apk.isFile || apk.length() <= 0L) {
            throw lastError ?: IllegalStateException("渲染器下载失败: ${pkg.displayName()}")
        }

        val dest = installDir(pkg).also {
            it.deleteRecursively()
            it.mkdirs()
        }
        val abiFolders = preferredAbiFolders()
        var extracted = 0
        ZipFile(apk).use { zip ->
            for (abi in abiFolders) {
                val prefix = "lib/$abi/"
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                    val name = entry.name.removePrefix(prefix)
                    if (name.contains('/') || !name.endsWith(".so")) continue
                    val out = File(dest, name)
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    out.setReadable(true, false)
                    out.setExecutable(true, false)
                    extracted++
                }
                if (extracted > 0) break
            }
            if (!File(dest, pkg.glLib).isFile) {
                val fallback = dest.listFiles()?.firstOrNull { f ->
                    f.isFile && matchesGlLib(pkg, f.name)
                }
                if (fallback != null && fallback.name != pkg.glLib) {
                    fallback.copyTo(File(dest, pkg.glLib), overwrite = true)
                }
            }
        }
        require(File(dest, pkg.glLib).isFile) {
            "渲染器包缺少 ${pkg.glLib}（已解压 $extracted 个 .so）"
        }
        File(dest, ".ready").writeText(pkg.downloadUrl)
        dest
    }

    private fun downloadCandidates(rawUrl: String): List<String> {
        // 国内直连 github 困难，先试代理。
        return listOf(
            "https://ghproxy.net/$rawUrl",
            "https://mirror.ghproxy.com/$rawUrl",
            "https://gitdl.cn/$rawUrl",
            rawUrl
        ).distinct()
    }

    private fun preferredAbiFolders(): List<String> {
        val supported = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val ordered = linkedSetOf<String>()
        for (abi in supported) {
            when (abi) {
                "arm64-v8a", "armeabi-v7a", "x86_64", "x86" -> ordered += abi
            }
        }
        ordered += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        return ordered.toList()
    }

    private fun matchesGlLib(pkg: RendererPackage, fileName: String): Boolean {
        if (fileName.equals(pkg.glLib, ignoreCase = true)) return true
        return when (pkg.kind) {
            com.booxin.launcher.core.launch.GlRendererKind.LTW ->
                fileName.contains("ltw", ignoreCase = true)
            com.booxin.launcher.core.launch.GlRendererKind.REL ->
                fileName.contains("rel", ignoreCase = true)
            com.booxin.launcher.core.launch.GlRendererKind.MCRENDER ->
                fileName.contains("mcrender", ignoreCase = true)
            com.booxin.launcher.core.launch.GlRendererKind.KRYPTON ->
                fileName.contains("ng_gl4es", ignoreCase = true)
            com.booxin.launcher.core.launch.GlRendererKind.VULKAN_ZINK,
            com.booxin.launcher.core.launch.GlRendererKind.VIRGL,
            com.booxin.launcher.core.launch.GlRendererKind.FREEDRENO ->
                fileName.contains("OSMesa", ignoreCase = true)
            com.booxin.launcher.core.launch.GlRendererKind.ANGLE ->
                fileName.contains("angle", ignoreCase = true)
            else -> false
        }
    }

    private fun RendererPackage.displayName(): String = kind.displayName
}
