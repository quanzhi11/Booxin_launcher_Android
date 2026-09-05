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
            com.booxin.launcher.core.runtime.RuntimeEnv.LEGACY_NATIVEDIR_ALT to stagedNatives,
            "_JAVA_VERSION_SET" to "true"
        )
        GameLaunchLogBus.latestLogFile()?.absolutePath?.let { path ->
            env[com.booxin.launcher.core.runtime.RuntimeEnv.LAUNCH_LOG] = path
        }
        if (OemLaunchProfile.isOplusFamily()) {
            // ColorOS can stall in GLFW.<clinit> before main; load GLFW later at glfwInit.
            // Does not change the selected GLES translator (REL stays REL).
            env["BOOXIN_SKIP_GLFW_PREINIT"] = "1"
            // Ignored SIGSEGV becomes a silent hang after Invoking main on ColorOS.
            env["BOOXIN_KEEP_SIGSEGV"] = "1"
        }
        env.putAll(extraEnv)

        env.forEach { (key, value) ->
            Os.setenv(key, value, true)
        }
        // Keep POJAV_RENDERER out of the Java-visible env (Create brands it "PojavLauncher"),
        // except legacy LWJGL2 which needs it in System.getenv (lwjglx NPE otherwise).
        val keepPojavRenderer = extraEnv.containsKey(
            com.booxin.launcher.core.runtime.RuntimeEnv.LEGACY_POJAV_RENDERER
        ) || extraEnv["BOOXIN_SKIP_GLFW_PREINIT"] == "1"
        if (!keepPojavRenderer) {
            runCatching { Os.unsetenv(com.booxin.launcher.core.runtime.RuntimeEnv.LEGACY_POJAV_RENDERER) }
        }

        loadGraphicsLibrary(stagedNatives)
        // JRE ships a stub libawt_xawt without Component.initIDs; replace with APK stub
        // so legacy Frame() clients (b1.8.1) can link (also System.load after CreateJavaVM).
        installAwtXawtStub(javaHome, stagedNatives)
        preloadLibraries(javaHome, jvmLibDir, stagedNatives)
        if (systemNative != stagedNatives) {
            preloadLibraries(javaHome, jvmLibDir, systemNative)
        }
    }

    /**
     * Android OpenJDK's bundled libawt_xawt.so has no Java_java_awt_Component_initIDs.
     * Our APK stub does — overwrite the JRE copy when the staged/APK stub is present.
     */
    private fun installAwtXawtStub(javaHome: File, stagedNatives: String) {
        val stub = sequenceOf(
            File(stagedNatives, "libawt_xawt.so"),
            File(stagedNatives, "../libawt_xawt.so")
        ).map { it.normalize() }.firstOrNull { it.isFile && it.length() > 0L }
            ?: return
        val targets = javaHome.walkTopDown().maxDepth(6)
            .filter { it.isFile && it.name == "libawt_xawt.so" }
            .toList()
        for (dest in targets) {
            if (dest.absolutePath == stub.absolutePath) continue
            if (dest.length() == stub.length() && dest.lastModified() >= stub.lastModified()) continue
            runCatching {
                stub.copyTo(dest, overwrite = true)
                dest.setReadable(true, false)
                dest.setExecutable(true, false)
                android.util.Log.i(
                    "BooxinJvm",
                    "replaced JRE libawt_xawt with Component.initIDs stub → ${dest.absolutePath}"
                )
            }.onFailure {
                android.util.Log.w("BooxinJvm", "awt_xawt stub install failed: ${it.message}")
            }
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
     * GLES 翻译库由 LWJGL libname 加载，这里不要提前 dlopen。
     */
    fun loadGraphicsLibrary(stagedNatives: String) {
        // MobileGlues 构造别在 ART 侧提前加载，交给 LWJGL。
        return
    }

    private fun preloadLibraries(javaHome: File, jvmLibDir: File, nativeLibDir: String) {
        val candidates = linkedSetOf<String>()
        // libjsig first: HotSpot signal chaining with ART (avoids CreateJavaVM hangs/crashes)
        findLibrary(javaHome, "libjsig.so")?.let { candidates += it.absolutePath }
        // Load in dependency order: jli → jvm → core JDK libs → extras
        findLibrary(javaHome, "libjli.so")?.let { candidates += it.absolutePath }
        // 优先 server/libjvm.so
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
            // javaagent needs instrument → tinyiconv; load tinyiconv first.
            "libtinyiconv.so",
            "libinstrument.so",
            "libmanagement.so",
            "libawt.so",
            "libawt_headless.so",
            "libfreetype.so",
            "libfontmanager.so"
        ).forEach { name ->
            findLibrary(javaHome, name)?.let { candidates += it.absolutePath }
        }
        // Do NOT preload stub libawt_xawt / libpojavexec_awt here:
        // - stub headless on top of JRE headless crashes CreateJavaVM
        // - pojavexec_awt needs libfcl.so (FCL-only)
        // Legacy LaunchWrapper loads awt_xawt via System.load after HotSpot starts.
        // Exec bridge is loaded via ExecBridgeLoader (single staged copy).
        // 不要预加载 LWJGL，Forge 1.21+ 自己加载。
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
