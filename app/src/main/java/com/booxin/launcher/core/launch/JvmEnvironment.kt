package com.booxin.launcher.core.launch

import android.content.Context
import android.system.Os
import com.booxin.launcher.core.java.InstalledJavaRuntime
import java.io.File

/**
 * Prepares LD_LIBRARY_PATH and preloads JRE shared libraries before JNI_CreateJavaVM.
 * Loads natives from app data (no exec of bin/java on Android).
 */
object JvmEnvironment {

    fun apply(
        context: Context,
        java: InstalledJavaRuntime,
        extraEnv: Map<String, String> = emptyMap()
    ) {
        AndroidGameRuntime.ensure(context)
        val javaHome = java.homeDir
        val stagedNatives = AndroidGameRuntime.nativesDir().absolutePath
        val systemNative = context.applicationInfo.nativeLibraryDir
        val ldLibraryPath = buildLdLibraryPath(javaHome, "$stagedNatives:$systemNative")
        val jvmLibDir = resolveJvmLibDir(javaHome)

        val env = linkedMapOf(
            "JAVA_HOME" to javaHome.absolutePath,
            "LD_LIBRARY_PATH" to ldLibraryPath,
            "PATH" to "${File(javaHome, "bin").absolutePath}:${Os.getenv("PATH").orEmpty()}",
            "HOME" to javaHome.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            com.booxin.launcher.core.runtime.RuntimeEnv.NATIVEDIR to stagedNatives,
            com.booxin.launcher.core.runtime.RuntimeEnv.LEGACY_POJAV_NATIVEDIR to stagedNatives,
            com.booxin.launcher.core.runtime.RuntimeEnv.LEGACY_FCL_NATIVEDIR to stagedNatives,
            "_JAVA_VERSION_SET" to "true"
        )
        env.putAll(extraEnv)

        env.forEach { (key, value) ->
            Os.setenv(key, value, true)
        }

        loadGraphicsLibrary(stagedNatives)
        preloadLibraries(javaHome, jvmLibDir, stagedNatives)
        if (systemNative != stagedNatives) {
            preloadLibraries(javaHome, jvmLibDir, systemNative)
        }
    }

    fun buildLdLibraryPath(javaHome: File, nativeLibDir: String): String {
        val parts = linkedSetOf<String>()
        val archLib = findArchLibDir(javaHome)
        if (archLib != null) {
            parts += archLib.absolutePath
            File(archLib, "jli").takeIf { it.isDirectory }?.let { parts += it.absolutePath }
            File(archLib, "server").takeIf { it.isDirectory }?.let { parts += it.absolutePath }
            File(archLib, "client").takeIf { it.isDirectory }?.let { parts += it.absolutePath }
        }
        listOf(
            javaHome,
            File(javaHome, "lib"),
            File(javaHome, "lib/server"),
            File(javaHome, "lib/client"),
            File(javaHome, "lib/aarch64"),
            File(javaHome, "lib/aarch64/server"),
            File(javaHome, "lib/aarch64/client"),
            File(javaHome, "jre/lib"),
            File(javaHome, "jre/lib/server"),
            File(javaHome, "jre/lib/client")
        ).filter { it.exists() }.forEach { parts += it.absolutePath }
        parts += nativeLibDir
        parts += "/system/lib64"
        parts += "/system/lib"
        parts += "/vendor/lib64"
        parts += "/vendor/lib"
        return parts.joinToString(":")
    }

    /**
     * GLES translator is loaded by LWJGL via libname — do not early-dlopen here.
     */
    fun loadGraphicsLibrary(stagedNatives: String) {
        // MobileGlues constructors fight ART; gl4es may be disguised. Leave to LWJGL.
        return
    }

    private fun preloadLibraries(javaHome: File, jvmLibDir: File, nativeLibDir: String) {
        val candidates = linkedSetOf<String>()
        // Load in dependency order: jli → jvm → core JDK libs → extras
        findLibrary(javaHome, "libjli.so")?.let { candidates += it.absolutePath }
        // Prefer server/libjvm.so, then client/libjvm.so
        listOf("server", "client", "").forEach { sub ->
            val dir = if (sub.isEmpty()) jvmLibDir else File(jvmLibDir.parentFile ?: javaHome, sub)
            File(dir, "libjvm.so").takeIf { it.isFile }?.let { candidates += it.absolutePath }
        }
        // Also walk to find any libjvm.so in case layout differs
        findLibrary(javaHome, "libjvm.so")?.let { candidates += it.absolutePath }

        listOf(
            "libverify.so",
            "libjava.so",
            "libnet.so",
            "libnio.so",
            "libzip.so",
            "libinstrument.so",
            "libmanagement.so",
            "libawt.so",
            "libawt_headless.so",
            "libfreetype.so",
            "libfontmanager.so"
        ).forEach { name ->
            findLibrary(javaHome, name)?.let { candidates += it.absolutePath }
        }
        // Exec bridge is loaded via ExecBridgeLoader (single staged copy).
        // Don't preload LWJGL — Forge 1.21+ module layer loads it itself.
        listOf("libc++_shared.so").forEach { name ->
            File(nativeLibDir, name).takeIf { it.isFile }?.let { candidates += it.absolutePath }
        }

        candidates.forEach { path ->
            runCatching { NativeJvmLauncher.preloadLibrary(path) }
        }
    }

    private fun resolveJvmLibDir(javaHome: File): File {
        val server = findLibrary(javaHome, "libjvm.so", prefer = listOf("server", "client"))
        return server?.parentFile ?: File(javaHome, "lib/server")
    }

    private fun findArchLibDir(javaHome: File): File? {
        val lib = File(javaHome, "lib")
        if (!lib.isDirectory) return null
        val archNames = listOf("aarch64", "arm", "arm64", "x86_64", "x86", "amd64")
        archNames.firstOrNull { File(lib, it).isDirectory }?.let { return File(lib, it) }
        return lib.listFiles()?.firstOrNull { it.isDirectory && it.name != "jfr" }
    }

    private fun findLibrary(
        javaHome: File,
        name: String,
        prefer: List<String> = emptyList()
    ): File? {
        val matches = javaHome.walkTopDown().maxDepth(8).filter { it.isFile && it.name == name }.toList()
        if (matches.isEmpty()) return null
        if (prefer.isNotEmpty()) {
            prefer.firstNotNullOfOrNull { folder ->
                matches.firstOrNull { it.parentFile?.name == folder }
            }?.let { return it }
        }
        return matches.first()
    }

    private fun locateSharedObjects(root: File): List<File> {
        return root.walkTopDown().maxDepth(8).filter { it.isFile && it.name.endsWith(".so") }.toList()
    }
}
