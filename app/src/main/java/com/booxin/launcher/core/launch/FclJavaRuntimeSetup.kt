package com.booxin.launcher.core.launch

import android.content.Context
import android.os.Build
import android.system.Os
import com.booxin.launcher.core.java.InstalledJavaRuntime
import java.io.File

/**
 * FCL FCLauncher.setUpJavaRuntime + getLibraryPath for API Installer (ProcessService).
 * No pojavexec / GLFW / renderer — only JRE native libs.
 */
object FclJavaRuntimeSetup {

    fun apply(context: Context, java: InstalledJavaRuntime, tmpDir: File) {
        val javaPath = java.homeDir.absolutePath
        val ldPath = buildLibraryPath(context, java.homeDir)
        Os.setenv("JAVA_HOME", javaPath, true)
        Os.setenv("HOME", context.cacheDir.absolutePath, true)
        Os.setenv("TMPDIR", tmpDir.absolutePath, true)
        Os.setenv("LD_LIBRARY_PATH", ldPath, true)
        Os.setenv("PATH", "${File(java.homeDir, "bin").absolutePath}:${Os.getenv("PATH").orEmpty()}", true)
        Os.setenv("FCL_NATIVEDIR", context.applicationInfo.nativeLibraryDir, true)
        Os.setenv("POJAV_NATIVEDIR", context.applicationInfo.nativeLibraryDir, true)
        Os.setenv("_JAVA_VERSION_SET", "true", true)
        setupJavaRuntime(java.homeDir)
    }

    /** FCL getLibraryPath(context, javaPath, pluginLibPath) */
    fun buildLibraryPath(context: Context, javaHome: File): String {
        val parts = linkedSetOf<String>()
        val javaPath = javaHome.absolutePath
        val javaLibDir = resolveJavaLibDir(javaHome)
        val jvmSub = resolveJvmSubDir(javaHome, javaLibDir)
        val libRoot = if (isJdk8(javaHome)) {
            File(javaHome, "jre/$javaLibDir")
        } else {
            File(javaHome, javaLibDir)
        }
        parts += libRoot.absolutePath
        File(libRoot, "jli").takeIf { it.isDirectory }?.let { parts += it.absolutePath }
        File(libRoot, jvmSub).takeIf { it.isDirectory }?.let { parts += it.absolutePath }
        if (isJdk8(javaHome)) {
            parts += File(javaHome, "jre/$javaLibDir").absolutePath
        }
        appendCommonPaths(context, parts)
        return parts.joinToString(":")
    }

    /** FCL setUpJavaRuntime */
    private fun setupJavaRuntime(javaHome: File) {
        val javaLibDir = resolveJavaLibDir(javaHome)
        val libRoot = if (isJdk8(javaHome)) {
            File(javaHome, "jre/$javaLibDir")
        } else {
            File(javaHome, javaLibDir)
        }
        val jliDir = File(libRoot, "jli").takeIf { File(it, "libjli.so").isFile } ?: libRoot
        val jvmDir = File(libRoot, resolveJvmSubDir(javaHome, javaLibDir))
        listOf(
            File(jliDir, "libjli.so"),
            File(jvmDir, "libjvm.so"),
            File(libRoot, "libfreetype.so"),
            File(libRoot, "libverify.so"),
            File(libRoot, "libjava.so"),
            File(libRoot, "libnet.so"),
            File(libRoot, "libnio.so"),
            File(libRoot, "libzip.so"),
            File(libRoot, "libinstrument.so"),
            File(libRoot, "libawt.so"),
            File(libRoot, "libawt_headless.so"),
            File(libRoot, "libfontmanager.so")
        ).forEach { lib ->
            if (lib.isFile) {
                runCatching { NativeJvmLauncher.preloadLibrary(lib.absolutePath) }
            }
        }
        locateLibs(javaHome).forEach { lib ->
            runCatching { NativeJvmLauncher.preloadLibrary(lib.absolutePath) }
        }
    }

    private fun locateLibs(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().maxDepth(12).filter { it.isFile && it.name.endsWith(".so") }.toList()
    }

    private fun appendCommonPaths(context: Context, parts: LinkedHashSet<String>) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libDirName = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "lib64" else "lib"
        parts += "/system/$libDirName"
        parts += "/vendor/$libDirName"
        parts += "/vendor/$libDirName/hw"
        parts += "/system_ext/$libDirName"
        parts += nativeDir
    }

    private fun resolveJavaLibDir(javaHome: File): String {
        val release = File(javaHome, "release")
        if (release.isFile) {
            release.readLines().forEach { line ->
                if (line.startsWith("OS_ARCH=")) {
                    val arch = line.substringAfter("=").trim().trim('"')
                    val mapped = when {
                        arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
                        arch.contains("arm") -> "arm"
                        arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
                        arch.contains("x86") || arch.contains("i386") -> "x86"
                        else -> arch
                    }
                    if (File(javaHome, "lib/$mapped").isDirectory) return "lib/$mapped"
                    arch.split("/").forEach { candidate ->
                        if (File(javaHome, "lib/$candidate").isDirectory) return "lib/$candidate"
                    }
                }
            }
        }
        listOf("aarch64", "arm64", "arm", "x86_64", "x86", "amd64").forEach { arch ->
            if (File(javaHome, "lib/$arch").isDirectory) return "lib/$arch"
        }
        return "lib"
    }

    private fun resolveJvmSubDir(javaHome: File, javaLibDir: String): String {
        val base = if (isJdk8(javaHome)) "jre/$javaLibDir" else javaLibDir
        val server = File(javaHome, "$base/server/libjvm.so")
        return if (server.isFile) "server" else "client"
    }

    private fun isJdk8(javaHome: File): Boolean {
        return File(javaHome, "jre").isDirectory && File(javaHome, "bin/javac").isFile
    }
}
