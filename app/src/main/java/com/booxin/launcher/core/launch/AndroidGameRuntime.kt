package com.booxin.launcher.core.launch

import android.content.Context
import android.os.Build
import com.booxin.launcher.core.LauncherPaths
import java.io.File
import java.util.zip.ZipFile

/**
 * Ensures FCL/Pojav-style Android runtime bits (patched LWJGL jar, optional JNA,
 * and native .so files on a real filesystem path) are available under app storage.
 *
 * With extractNativeLibs=false, nativeLibraryDir is empty and LWJGL cannot find
 * liblwjgl.so by path — we always stage natives under filesDir.
 */
object AndroidGameRuntime {

    private const val ASSET_LWJGL = "app_runtime/lwjgl/lwjgl.jar"
    private const val ASSET_LWJGL_PATCH = "app_runtime/lwjgl/lwjgl-bridge-patch.jar"
    private const val ASSET_LWJGL_VERSION = "app_runtime/lwjgl/version"
    private const val ASSET_JNA_PREFIX = "app_runtime/jna/"

    private val NATIVE_NAMES = listOf(
        "liblwjgl.so",
        "liblwjgl_opengl.so",
        "liblwjgl_stb.so",
        "liblwjgl_tinyfd.so",
        "liblwjgl_vma.so",
        "liblwjgl_nanovg.so",
        // Prefer MobileGlues for 1.21+; keep gl4es staged only as optional fallback name
        // but LaunchCommandBuilder points POJAV_RENDERER at libmobileglues.so.
        "libmobileglues.so",
        "libmobileglues_info_getter.so",
        "libgl4es_114.so",
        "libopenal.so",
        "libpojavexec.so",
        "libpojavexec_awt.so",
        "libdriver_helper.so",
        "libawt_xawt.so",
        "libawt_headless.so",
        "libc++_shared.so",
        "libfreetype.so",
        "libshaderc.so",
        "libspirv-cross-c-shared.so",
        "libfcl.so",
        "libbytehook.so",
        "liblinkerhook.so",
        "libbooxin_jvm.so"
    )

    fun lwjglJar(): File = File(LauncherPaths.runtimeDir, "lwjgl/lwjgl.jar")

    /** Must be ahead of [lwjglJar] on the classpath (CallbackBridge JNI patch). */
    fun lwjglBridgePatchJar(): File = File(LauncherPaths.runtimeDir, "lwjgl/lwjgl-bridge-patch.jar")

    fun nativesDir(): File = File(LauncherPaths.runtimeDir, "natives")

    fun ensure(context: Context) {
        LauncherPaths.init(context)
        ensureLwjgl(context)
        ensureJna(context)
        ensureNatives(context)
        stageJnaDispatch()
    }

    private fun ensureLwjgl(context: Context) {
        val destDir = File(LauncherPaths.runtimeDir, "lwjgl").also { it.mkdirs() }
        val destJar = File(destDir, "lwjgl.jar")
        val destPatch = File(destDir, "lwjgl-bridge-patch.jar")
        val destVer = File(destDir, "version")
        val assetVer = runCatching {
            context.assets.open(ASSET_LWJGL_VERSION).bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
        val needCopy = !destJar.isFile || destJar.length() == 0L ||
            !destPatch.isFile || destPatch.length() == 0L ||
            (assetVer.isNotEmpty() && destVer.takeIf { it.isFile }?.readText()?.trim() != assetVer)
        if (!needCopy) return
        context.assets.open(ASSET_LWJGL).use { input ->
            destJar.outputStream().use { output -> input.copyTo(output) }
        }
        runCatching {
            context.assets.open(ASSET_LWJGL_PATCH).use { input ->
                destPatch.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (assetVer.isNotEmpty()) {
            destVer.writeText(assetVer)
        }
    }

    private fun ensureJna(context: Context) {
        val destDir = File(LauncherPaths.runtimeDir, "jna").also { it.mkdirs() }
        val marker = File(destDir, ".extracted")
        if (marker.isFile && File(destDir, "jna").isDirectory) return
        val names = runCatching { context.assets.list("app_runtime/jna")?.toList().orEmpty() }
            .getOrDefault(emptyList())
        if (names.isEmpty()) return
        for (name in names) {
            val assetPath = ASSET_JNA_PREFIX + name
            if (name.endsWith(".zip")) {
                val tmp = File(destDir, name)
                context.assets.open(assetPath).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                unzipPreserve(tmp, destDir)
                tmp.delete()
            } else {
                val out = File(destDir, name)
                context.assets.open(assetPath).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        marker.writeText("1")
    }

    /** Copy Android libjnidispatch.so next to other staged natives for JNA. */
    private fun stageJnaDispatch() {
        val dest = File(nativesDir(), "libjnidispatch.so")
        if (dest.isFile && dest.length() > 0L) return
        val versionRoot = File(LauncherPaths.runtimeDir, "jna/jna")
        if (!versionRoot.isDirectory) return
        val preferred = listOf("5.15.0", "5.16.0", "5.14.0", "5.13.0")
        val versions = versionRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            .orEmpty()
        val source = preferred.asSequence()
            .map { File(versionRoot, "$it/libjnidispatch.so") }
            .firstOrNull { it.isFile }
            ?: versions.asSequence()
                .map { File(it, "libjnidispatch.so") }
                .firstOrNull { it.isFile }
            ?: return
        source.copyTo(dest, overwrite = true)
        dest.setReadable(true, false)
        dest.setExecutable(true, false)
    }

    fun ensureNatives(context: Context) {
        val dest = nativesDir().also { it.mkdirs() }
        val marker = File(dest, ".ready")
        val expected = "v6:${NATIVE_NAMES.size}:${preferredAbiFolder()}"
        val markerOk = marker.isFile && marker.readText().trim().startsWith("v6:")
        val missingRequired = !File(dest, "liblwjgl.so").isFile ||
            !File(dest, "libpojavexec.so").isFile ||
            !File(dest, "libdriver_helper.so").isFile ||
            !File(dest, "libmobileglues.so").isFile
        if (markerOk && !missingRequired) {
            // Still fill any newly-added names without wiping existing files.
            syncMissingNatives(context, dest)
            hideGl4esIfMobileGluesPresent(dest)
            return
        }

        val abiFolder = preferredAbiFolder()
        var copied = 0

        val systemNative = File(context.applicationInfo.nativeLibraryDir)
        if (systemNative.isDirectory) {
            for (name in NATIVE_NAMES) {
                val src = File(systemNative, name)
                if (!src.isFile) continue
                src.copyTo(File(dest, name), overwrite = true)
                copied++
            }
        }

        copied += extractMissingFromApk(context, dest, abiFolder)

        require(File(dest, "liblwjgl.so").isFile) {
            "无法准备 liblwjgl.so（abi=$abiFolder, copied=$copied, nativeDir=${systemNative.absolutePath}）"
        }
        require(File(dest, "libdriver_helper.so").isFile) {
            "无法准备 libdriver_helper.so（pojavexec 依赖）"
        }

        dest.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".so")) {
                f.setReadable(true, false)
                f.setExecutable(true, false)
            }
        }
        linkNativeAlias(dest, "libspirv-cross-c-shared.so", "libspirv-cross.so")
        hideGl4esIfMobileGluesPresent(dest)
        marker.writeText("$expected:$copied")
    }

    private fun hideGl4esIfMobileGluesPresent(dest: File) {
        val mg = File(dest, "libmobileglues.so")
        if (!mg.isFile) return
        // pojavexec LWJGL hook loads "libgl4es_114.so" for POJAV_RENDERER=opengles3.
        // Replace that filename with MobileGlues so the game does not get holy-gl4es.
        val gl4 = File(dest, "libgl4es_114.so")
        mg.copyTo(gl4, overwrite = true)
        gl4.setReadable(true, false)
        gl4.setExecutable(true, false)
    }

    /** LWJGL default name vs FCL-packaged .so filename. */
    private fun linkNativeAlias(dest: File, sourceName: String, aliasName: String) {
        val source = File(dest, sourceName)
        val alias = File(dest, aliasName)
        if (!source.isFile || alias.isFile) return
        source.copyTo(alias, overwrite = true)
        alias.setReadable(true, false)
        alias.setExecutable(true, false)
    }

    private fun syncMissingNatives(context: Context, dest: File) {
        val missing = NATIVE_NAMES.any { !File(dest, it).isFile }
        if (!missing) return
        extractMissingFromApk(context, dest, preferredAbiFolder())
        linkNativeAlias(dest, "libspirv-cross-c-shared.so", "libspirv-cross.so")
        dest.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".so")) {
                f.setReadable(true, false)
                f.setExecutable(true, false)
            }
        }
    }

    private fun extractMissingFromApk(context: Context, dest: File, abiFolder: String): Int {
        var copied = 0
        val needNames = NATIVE_NAMES.filter { !File(dest, it).isFile }.toMutableSet()
        // Always allow any liblwjgl* from the APK.
        val apk = File(context.applicationInfo.sourceDir)
        if (!apk.isFile || (needNames.isEmpty())) return 0
        ZipFile(apk).use { zip ->
            val prefix = "lib/$abiFolder/"
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                val name = entry.name.removePrefix(prefix)
                if (name.contains('/') || !name.endsWith(".so")) continue
                val wanted = name in needNames || (name.startsWith("liblwjgl") && !File(dest, name).isFile)
                if (!wanted) continue
                val out = File(dest, name)
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                needNames.remove(name)
                copied++
            }
        }
        return copied
    }

    private fun preferredAbiFolder(): String {
        val primary = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            primary.startsWith("arm64") -> "arm64-v8a"
            primary.startsWith("armeabi") || primary == "armv7l" -> "armeabi-v7a"
            primary.startsWith("x86_64") -> "x86_64"
            primary.startsWith("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }

    private fun unzipPreserve(zipFile: File, destDir: File) {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val out = File(destDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
