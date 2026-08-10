package com.booxin.launcher.core.plugin

import android.os.Build
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.net.FileDownloader
import com.booxin.launcher.core.runtime.RendererPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Downloads / imports plugin APKs and extracts ABI-matched natives into
 * `booxin-runtime/plugins/<id>/` (also writes `plugin.json` + `.ready`).
 */
object PluginInstaller {
    private val downloader = FileDownloader()

    fun pluginsRoot(): File = File(LauncherPaths.runtimeDir, "plugins").also { it.mkdirs() }

    /** Legacy renderers/ still used by [com.booxin.launcher.core.runtime.RendererInstaller]. */
    fun legacyRenderersRoot(): File =
        File(LauncherPaths.runtimeDir, "renderers").also { it.mkdirs() }

    fun cacheDir(): File = File(LauncherPaths.rootDir, "cache/plugins").also { it.mkdirs() }

    fun installDir(id: String): File = File(pluginsRoot(), id)

    fun isReady(dir: File, glLib: String): Boolean {
        val marker = File(dir, ".ready")
        val gl = File(dir, glLib)
        return marker.isFile && gl.isFile && gl.length() > 0L
    }

    suspend fun installBuiltin(pkg: RendererPackage): File = withContext(Dispatchers.IO) {
        val apk = downloadApk(pkg.id, pkg.downloadUrl)
        val manifest = PluginManifest(
            id = pkg.id,
            name = pkg.kind.displayName,
            version = "builtin",
            type = PluginType.RENDERER,
            glLib = pkg.glLib,
            eglLib = pkg.eglLib,
            rendererToken = pkg.rendererToken,
            libGlEs = pkg.libGlEs,
            extraEnv = pkg.extraEnv,
            kindName = pkg.kind.name,
            disguiseAsGl4es = pkg.disguiseAsGl4es
        )
        // Keep legacy path for existing RendererInstaller consumers.
        val legacy = File(legacyRenderersRoot(), pkg.id)
        extractApk(apk, legacy, pkg.glLib)
        File(legacy, ".ready").writeText(pkg.downloadUrl)
        File(legacy, "plugin.json").writeText(manifest.toJson().toString())

        val dest = installDir(pkg.id)
        if (dest.absolutePath != legacy.absolutePath) {
            dest.deleteRecursively()
            legacy.copyRecursively(dest, overwrite = true)
        }
        dest
    }

    suspend fun installFromUrl(id: String, url: String, name: String = id): File =
        withContext(Dispatchers.IO) {
            val apk = downloadApk(id, url)
            installFromApkFile(apk, preferredId = id, preferredName = name)
        }

    suspend fun installFromApkFile(
        apk: File,
        preferredId: String? = null,
        preferredName: String? = null
    ): File = withContext(Dispatchers.IO) {
        require(apk.isFile && apk.length() > 0L) { "APK 无效" }
        val embedded = readEmbeddedManifest(apk)
        val guessedGl = embedded?.glLib ?: guessGlLib(apk)
            ?: error("无法识别插件 GL 库（缺少 plugin.json / 已知 .so）")
        val id = (embedded?.id ?: preferredId ?: apk.nameWithoutExtension)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "plugin-${System.currentTimeMillis()}" }
        val manifest = embedded?.copy(
            id = id,
            name = preferredName?.takeIf { it.isNotBlank() } ?: embedded.name
        ) ?: PluginManifest(
            id = id,
            name = preferredName?.takeIf { it.isNotBlank() } ?: id,
            version = "import",
            type = PluginType.RENDERER,
            glLib = guessedGl,
            disguiseAsGl4es = guessedGl.contains("gl4es", ignoreCase = true) ||
                guessedGl.contains("ltw", ignoreCase = true) ||
                guessedGl.contains("ng_gl4es", ignoreCase = true)
        )
        val dest = installDir(id).also {
            it.deleteRecursively()
            it.mkdirs()
        }
        extractApk(apk, dest, manifest.glLib)
        File(dest, "plugin.json").writeText(manifest.toJson().toString())
        File(dest, ".ready").writeText(apk.absolutePath)
        // Mirror into legacy renderers/ for RendererInstaller / launch path.
        val legacy = File(legacyRenderersRoot(), id)
        if (legacy.absolutePath != dest.absolutePath) {
            legacy.deleteRecursively()
            dest.copyRecursively(legacy, overwrite = true)
        }
        dest
    }

    fun uninstall(id: String) {
        installDir(id).deleteRecursively()
        File(legacyRenderersRoot(), id).deleteRecursively()
        PluginStateStore.remove(id)
    }

    private suspend fun downloadApk(id: String, url: String): File {
        val apk = File(cacheDir(), "$id.apk")
        var lastError: Throwable? = null
        for (candidate in downloadCandidates(url)) {
            val result = downloader.download(candidate, apk)
            if (result.isSuccess && apk.isFile && apk.length() > 0L) {
                return apk
            }
            lastError = result.exceptionOrNull()
            apk.delete()
        }
        throw lastError ?: IllegalStateException("插件下载失败: $id")
    }

    private fun downloadCandidates(rawUrl: String): List<String> =
        listOf(
            "https://ghproxy.net/$rawUrl",
            "https://mirror.ghproxy.com/$rawUrl",
            "https://gitdl.cn/$rawUrl",
            rawUrl
        ).distinct()

    private fun extractApk(apk: File, dest: File, expectedGlLib: String) {
        dest.mkdirs()
        val abiFolders = preferredAbiFolders()
        var extracted = 0
        ZipFile(apk).use { zip ->
            for (abi in abiFolders) {
                val prefix = "lib/$abi/"
                val entries = zip.entries()
                var abiCount = 0
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
                    abiCount++
                    extracted++
                }
                if (abiCount > 0) break
            }
            if (!File(dest, expectedGlLib).isFile) {
                val fallback = dest.listFiles()?.firstOrNull { f ->
                    f.isFile && f.name.endsWith(".so") &&
                        (f.name.equals(expectedGlLib, ignoreCase = true) ||
                            f.name.contains("OSMesa", ignoreCase = true) ||
                            f.name.contains("ltw", ignoreCase = true) ||
                            f.name.contains("angle", ignoreCase = true) ||
                            f.name.contains("gl4es", ignoreCase = true))
                }
                if (fallback != null && fallback.name != expectedGlLib) {
                    fallback.copyTo(File(dest, expectedGlLib), overwrite = true)
                }
            }
        }
        require(File(dest, expectedGlLib).isFile) {
            "插件缺少 $expectedGlLib（已解压 $extracted 个 .so）"
        }
    }

    private fun readEmbeddedManifest(apk: File): PluginManifest? = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("assets/plugin.json")
                ?: zip.getEntry("plugin.json")
                ?: return@use null
            zip.getInputStream(entry).bufferedReader().use { PluginManifest.parse(it.readText()) }
        }
    }.getOrNull()

    private fun guessGlLib(apk: File): String? = runCatching {
        ZipFile(apk).use { zip ->
            val abiFolders = preferredAbiFolders()
            for (abi in abiFolders) {
                val prefix = "lib/$abi/"
                val names = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith(prefix) && it.endsWith(".so") && !it.removePrefix(prefix).contains('/') }
                    .map { it.removePrefix(prefix) }
                    .toList()
                if (names.isEmpty()) continue
                return@use names.firstOrNull {
                    it.contains("OSMesa", ignoreCase = true) ||
                        it.contains("ltw", ignoreCase = true) ||
                        it.contains("angle", ignoreCase = true) ||
                        it.contains("gl4es", ignoreCase = true) ||
                        it.contains("mobileglues", ignoreCase = true)
                } ?: names.first()
            }
            null
        }
    }.getOrNull()

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
}
