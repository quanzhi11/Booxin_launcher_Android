package com.booxin.launcher.core.runtime

import android.os.Build
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.launch.GlRendererKind
import com.booxin.launcher.core.net.FileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Downloads FCL/Zalith renderer plugin APKs and extracts native libs for the device ABI.
 */
object RendererInstaller {
    private val downloader = FileDownloader()

    fun pluginsRoot(): File = File(LauncherPaths.runtimeDir, "renderers").also { it.mkdirs() }

    fun installDir(pkg: RendererPackage): File = File(pluginsRoot(), pkg.id)

    fun isInstalled(kind: GlRendererKind): Boolean {
        if (!kind.requiresPlugin) return true
        val pkg = RendererPackages.forKind(kind) ?: return false
        return isInstalled(pkg)
    }

    fun isInstalled(pkg: RendererPackage): Boolean {
        val dir = installDir(pkg)
        val marker = File(dir, ".ready")
        val gl = File(dir, pkg.glLib)
        return marker.isFile && gl.isFile && gl.length() > 0L
    }

    fun glLibrary(kind: GlRendererKind): File? {
        val pkg = RendererPackages.forKind(kind) ?: return null
        return File(installDir(pkg), pkg.glLib).takeIf { it.isFile }
    }

    fun pluginNativeDir(kind: GlRendererKind): File? {
        val pkg = RendererPackages.forKind(kind) ?: return null
        return installDir(pkg).takeIf { isInstalled(pkg) }
    }

    suspend fun ensureInstalled(kind: GlRendererKind): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (!kind.requiresPlugin) {
                return@runCatching File(LauncherPaths.runtimeDir, "natives")
            }
            val pkg = requireNotNull(RendererPackages.forKind(kind)) {
                "未知渲染器: ${kind.displayName}"
            }
            if (isInstalled(pkg)) return@runCatching installDir(pkg)
            install(pkg)
        }
    }

    suspend fun install(pkg: RendererPackage): File = withContext(Dispatchers.IO) {
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
            // Plugin APKs often rename the primary .so across releases.
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
        // CN networks often cannot reach github.com — try common proxies first.
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
            com.booxin.launcher.core.launch.GlRendererKind.KRYPTON ->
                fileName.contains("ng_gl4es", ignoreCase = true)
            com.booxin.launcher.core.launch.GlRendererKind.VULKAN_ZINK,
            com.booxin.launcher.core.launch.GlRendererKind.VIRGL,
            com.booxin.launcher.core.launch.GlRendererKind.FREEDRENO ->
                fileName.contains("OSMesa", ignoreCase = true)
            else -> false
        }
    }

    private fun RendererPackage.displayName(): String = kind.displayName
}
