package com.booxin.launcher.core.launch

import android.content.Context
import android.os.Build
import com.booxin.launcher.core.LauncherPaths
import java.io.File
import java.util.zip.ZipFile

/**
 * Copy LWJGL jar / natives into app filesDir.
 * extractNativeLibs=false leaves nativeLibraryDir empty, so LWJGL needs a real path.
 */
object AndroidGameRuntime {

    private const val ASSET_LWJGL = "app_runtime/lwjgl/lwjgl.jar"
    private const val ASSET_LWJGL_PATCH = "app_runtime/lwjgl/lwjgl-bridge-patch.jar"
    private const val ASSET_LWJGL_CORE_34 = "app_runtime/lwjgl/lwjgl-core-3.4.jar"
    private const val ASSET_LWJGL_JNI_SHIM = "app_runtime/lwjgl/lwjgl-jni-sdl-shim.jar"
    private const val ASSET_LWJGL_SDL = "app_runtime/lwjgl/lwjgl-sdl.jar"
    private const val ASSET_LWJGL_VERSION = "app_runtime/lwjgl/version"
    private const val ASSET_JNA_PREFIX = "app_runtime/jna/"

    private val NATIVE_NAMES = listOf(
        "liblwjgl.so",
        "liblwjgl_opengl.so",
        "liblwjgl_stb.so",
        "liblwjgl_tinyfd.so",
        "liblwjgl_vma.so",
        "liblwjgl_nanovg.so",
        "libSDL3.so",
        "libmobileglues.so",
        "libmobileglues_info_getter.so",
        "libgl4es_114.so",
        "libbooxingl.so",
        "libopenal.so",
        "libbooxin_bridge.so",
        "libbytehook.so",
        "libawt_xawt.so",
        "libawt_headless.so",
        "libc++_shared.so",
        "libfreetype.so",
        "libshaderc.so",
        "libspirv-cross-c-shared.so",
        "libbooxin_jvm.so"
    )

    fun lwjglJar(): File = File(LauncherPaths.runtimeDir, "lwjgl/lwjgl.jar")

    /** LWJGL 3.4 core（完整桌面包；一般不要整包前置，会撞 Java25 FFM）。 */
    fun lwjglCore34Jar(): File = File(LauncherPaths.runtimeDir, "lwjgl/lwjgl-core-3.4.jar")

    /**
     * 仅含 3.4 基线 `JNI.class`（native invokePZ…），供 SDL 路径 RegisterNatives。
     * 不含 META-INF/versions/25，避免非 native FFM 实现。
     */
    fun lwjglJniSdlShimJar(): File = File(LauncherPaths.runtimeDir, "lwjgl/lwjgl-jni-sdl-shim.jar")

    /** LWJGL SDL 绑定（MC 26.3+）。 */
    fun lwjglSdlJar(): File = File(LauncherPaths.runtimeDir, "lwjgl/lwjgl-sdl.jar")

    /**
     * 旧版独立 bridge-patch jar。
     * Forge 1.21+ 不要单独上 classpath，应合并进 [lwjglJar]。
     */
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
        val destCore34 = File(destDir, "lwjgl-core-3.4.jar")
        val destJniShim = File(destDir, "lwjgl-jni-sdl-shim.jar")
        val destSdl = File(destDir, "lwjgl-sdl.jar")
        val destVer = File(destDir, "version")
        val assetVer = runCatching {
            context.assets.open(ASSET_LWJGL_VERSION).bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
        val needCopy = !destJar.isFile || destJar.length() == 0L ||
            !destPatch.isFile || destPatch.length() == 0L ||
            !destCore34.isFile || destCore34.length() == 0L ||
            !destJniShim.isFile || destJniShim.length() == 0L ||
            !destSdl.isFile || destSdl.length() == 0L ||
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
        runCatching {
            context.assets.open(ASSET_LWJGL_CORE_34).use { input ->
                destCore34.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure {
            android.util.Log.w("BooxinRuntime", "lwjgl-core-3.4.jar missing from assets: ${it.message}")
        }
        runCatching {
            context.assets.open(ASSET_LWJGL_JNI_SHIM).use { input ->
                destJniShim.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure {
            android.util.Log.w("BooxinRuntime", "lwjgl-jni-sdl-shim.jar missing from assets: ${it.message}")
        }
        runCatching {
            context.assets.open(ASSET_LWJGL_SDL).use { input ->
                destSdl.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure {
            android.util.Log.w("BooxinRuntime", "lwjgl-sdl.jar missing from assets: ${it.message}")
        }
        if (assetVer.isNotEmpty()) {
            destVer.writeText(assetVer)
        }
    }

    private fun ensureJna(context: Context) {
        val destDir = File(LauncherPaths.runtimeDir, "jna").also { it.mkdirs() }
        val marker = File(destDir, ".extracted")
        val assetVer = runCatching {
            context.assets.open(ASSET_JNA_PREFIX + "version").bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
        val has517 = File(destDir, "jna/5.17.0/libjnidispatch.so").isFile
        val markerOk = marker.isFile &&
            File(destDir, "jna").isDirectory &&
            has517 &&
            (assetVer.isEmpty() || marker.readText().trim() == assetVer)
        if (markerOk) return
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
        marker.writeText(assetVer.ifBlank { "1" })
    }

    /**
     * Directory containing Android-built libjnidispatch.so matching [jnaJarVersion]
     * (e.g. "5.17.0" from net.java.dev.jna:jna:5.17.0).
     *
     * jna.nounpack=true forbids extracting glibc natives from the Maven jar;
     * a version mismatch here breaks com.sun.jna.Native and cascades into OSHI
     * NoClassDefFoundError: oshi/util/tuples/Quartet under Forge.
     */
    fun jnaBootLibraryPath(jnaJarVersion: String?): String {
        val versionRoot = File(LauncherPaths.runtimeDir, "jna/jna")
        val exact = jnaJarVersion?.let { File(versionRoot, it) }
        if (exact != null && File(exact, "libjnidispatch.so").isFile) {
            return exact.absolutePath
        }
        // Prefer the newest available native that is still compatible (same major.minor family).
        val available = versionRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "libjnidispatch.so").isFile }
            .orEmpty()
        if (available.isEmpty()) return nativesDir().absolutePath
        val target = parseJnaVersion(jnaJarVersion)
        val best = available.maxWithOrNull { a, b ->
            compareJnaVersion(parseJnaVersion(a.name), parseJnaVersion(b.name))
        }
        if (target == null) {
            return best?.absolutePath ?: nativesDir().absolutePath
        }
        // Exact major.minor match first (5.17.x → 5.17.0), else closest lower, else newest.
        val sameMinor = available.filter {
            val v = parseJnaVersion(it.name) ?: return@filter false
            v.first == target.first && v.second == target.second
        }.maxWithOrNull { a, b ->
            compareJnaVersion(parseJnaVersion(a.name), parseJnaVersion(b.name))
        }
        if (sameMinor != null) return sameMinor.absolutePath
        val lowerOrEqual = available.filter {
            compareJnaVersion(parseJnaVersion(it.name), target) <= 0
        }.maxWithOrNull { a, b ->
            compareJnaVersion(parseJnaVersion(a.name), parseJnaVersion(b.name))
        }
        return (lowerOrEqual ?: best)?.absolutePath ?: nativesDir().absolutePath
    }

    /** Copy matching Android libjnidispatch.so next to other staged natives. */
    private fun stageJnaDispatch() {
        val dest = File(nativesDir(), "libjnidispatch.so")
        val sourceDir = File(jnaBootLibraryPath(null))
        val source = File(sourceDir, "libjnidispatch.so")
        if (!source.isFile) return
        if (dest.isFile && dest.length() == source.length() && dest.length() > 0L) return
        source.copyTo(dest, overwrite = true)
        dest.setReadable(true, false)
        dest.setExecutable(true, false)
    }

    private fun parseJnaVersion(version: String?): Triple<Int, Int, Int>? {
        if (version.isNullOrBlank()) return null
        val parts = version.split('.')
        return Triple(
            parts.getOrNull(0)?.toIntOrNull() ?: return null,
            parts.getOrNull(1)?.toIntOrNull() ?: 0,
            parts.getOrNull(2)?.toIntOrNull() ?: 0
        )
    }

    private fun compareJnaVersion(a: Triple<Int, Int, Int>?, b: Triple<Int, Int, Int>?): Int {
        if (a == null && b == null) return 0
        if (a == null) return -1
        if (b == null) return 1
        return compareValuesBy(a, b, { it.first }, { it.second }, { it.third })
    }

    fun ensureNatives(context: Context) {
        val dest = nativesDir().also { it.mkdirs() }
        val marker = File(dest, ".ready")
        val expected = "v23:${NATIVE_NAMES.size}:${preferredAbiFolder()}"
        val markerOk = marker.isFile && marker.readText().trim().startsWith("v23:")
        val missingRequired = !File(dest, "liblwjgl.so").isFile ||
            !File(dest, "libbooxin_bridge.so").isFile ||
            !File(dest, "libpojavexec.so").isFile ||
            !File(dest, "libmobileglues.so").isFile ||
            !File(dest, "libgl4es_114.so").isFile ||
            !File(dest, "libSDL3.so").isFile ||
            !File(dest, "libbytehook.so").isFile
        if (markerOk && !missingRequired) {
            syncMissingNatives(context, dest)
            ensureHolyGl4esBackup(context, dest)
            refreshBridgeFromApk(context, dest)
            refreshMobileGluesFromApk(context, dest)
            refreshBytehookFromApk(context, dest)
            installBridgeCompatAlias(dest)
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
        require(File(dest, "libbooxin_bridge.so").isFile) {
            "无法准备 libbooxin_bridge.so（自研运行时桥）"
        }

        dest.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".so")) {
                f.setReadable(true, false)
                f.setExecutable(true, false)
            }
        }
        linkNativeAlias(dest, "libspirv-cross-c-shared.so", "libspirv-cross.so")
        installBridgeCompatAlias(dest)
        ensureHolyGl4esBackup(context, dest)
        // Wipe old leftover .so copies; keep the LWJGL bridge soname alias.
        listOf(
            "libpojavexec.so",
            "libpojavexec_awt.so",
            "libfcl.so",
            "liblinkerhook.so",
            "libdriver_helper.so"
        ).forEach { name ->
            if (name == "libpojavexec.so") return@forEach
            File(dest, name).takeIf { it.isFile }?.delete()
        }
        marker.writeText("$expected:$copied")
    }

    /** Stage the bridge under the soname LWJGL expects. */
    private fun installBridgeCompatAlias(dest: File) {
        val bridge = File(dest, "libbooxin_bridge.so")
        if (!bridge.isFile) return
        val alias = File(dest, "libpojavexec.so")
        if (!alias.isFile || alias.length() != bridge.length() || alias.lastModified() < bridge.lastModified()) {
            bridge.copyTo(alias, overwrite = true)
            alias.setReadable(true, false)
            alias.setExecutable(true, false)
        }
    }

    private fun refreshBridgeFromApk(context: Context, dest: File) {
        val systemNative = File(context.applicationInfo.nativeLibraryDir)
        var refreshed = 0
        val bridgeName = "libbooxin_bridge.so"
        val fromApk = File(systemNative, bridgeName)
        val out = File(dest, bridgeName)
        // Always refresh bridge from the installed APK — launch path is sensitive to stale .so.
        if (fromApk.isFile) {
            fromApk.copyTo(out, overwrite = true)
            out.setReadable(true, false)
            out.setExecutable(true, false)
            refreshed++
        }
        val bridge = File(dest, bridgeName)
        val alias = File(dest, "libpojavexec.so")
        if (bridge.isFile) {
            bridge.copyTo(alias, overwrite = true)
            alias.setReadable(true, false)
            alias.setExecutable(true, false)
            refreshed++
        }
        if (refreshed > 0) {
            android.util.Log.i("BooxinRuntime", "refreshed bridge natives count=$refreshed")
        }
    }

    /** 26.3+ SDL needs bytehook to redirect libEGL → MobileGlues; keep APK copy staged. */
    private fun refreshBytehookFromApk(context: Context, dest: File) {
        val fromApk = File(context.applicationInfo.nativeLibraryDir, "libbytehook.so")
        val out = File(dest, "libbytehook.so")
        if (!fromApk.isFile) return
        if (out.isFile && out.length() == fromApk.length() && out.lastModified() >= fromApk.lastModified()) {
            return
        }
        fromApk.copyTo(out, overwrite = true)
        out.setReadable(true, false)
        out.setExecutable(true, false)
        android.util.Log.i("BooxinRuntime", "refreshed libbytehook.so from APK")
    }

    /**
     * Always re-copy MobileGlues from the installed APK.
     * Experimental REL builds previously left librel.so staged as the active
     * translator and broke every version with "no OpenGL context".
     */
    private fun refreshMobileGluesFromApk(context: Context, dest: File) {
        val systemNative = File(context.applicationInfo.nativeLibraryDir)
        val names = listOf("libmobileglues.so", "libmobileglues_info_getter.so")
        var refreshed = 0
        for (name in names) {
            val fromApk = File(systemNative, name)
            val out = File(dest, name)
            if (!fromApk.isFile) continue
            if (out.isFile && out.length() == fromApk.length() &&
                out.lastModified() >= fromApk.lastModified()
            ) {
                continue
            }
            fromApk.copyTo(out, overwrite = true)
            out.setReadable(true, false)
            out.setExecutable(true, false)
            refreshed++
        }
        // Drop leftover experimental REL translator if present under natives/.
        listOf("librel.so", "libREL.so").forEach { name ->
            File(dest, name).takeIf { it.isFile }?.delete()
        }
        if (refreshed > 0) {
            android.util.Log.i("BooxinRuntime", "refreshed MobileGlues natives count=$refreshed")
        }
    }

    /** 备份真实 gl4es（libgl4es_114 可能被换成 MobileGlues）。 */
    private fun ensureHolyGl4esBackup(context: Context, dest: File) {
        val holy = File(dest, "libgl4es_holy.so")
        val gl4 = File(dest, "libgl4es_114.so")
        val mg = File(dest, "libmobileglues.so")
        val gl4IsMgDisguise = gl4.isFile && mg.isFile && gl4.length() == mg.length()
        if (gl4IsMgDisguise || !gl4.isFile) {
            extractNamedFromApk(context, dest, preferredAbiFolder(), "libgl4es_114.so")
        }
        if (gl4.isFile && (!mg.isFile || gl4.length() != mg.length())) {
            if (!holy.isFile || holy.length() != gl4.length()) {
                gl4.copyTo(holy, overwrite = true)
            }
        } else if (!holy.isFile || (mg.isFile && holy.length() == mg.length())) {
            // Pull holy directly from APK into the backup name.
            val apk = File(context.applicationInfo.sourceDir)
            if (apk.isFile) {
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("lib/${preferredAbiFolder()}/libgl4es_114.so") ?: return@use
                    zip.getInputStream(entry).use { input ->
                        holy.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        holy.takeIf { it.isFile }?.apply {
            setReadable(true, false)
            setExecutable(true, false)
        }
        gl4.takeIf { it.isFile }?.apply {
            setReadable(true, false)
            setExecutable(true, false)
        }
    }

    /**
     * Stages the active translator filename expected by LWJGL.
     * - [GlRendererKind.GL4ES]: restore holy → libgl4es_114.so
     * - [GlRendererKind.BOOXIN_GLUES]: Path A MIT stack (GL4ES / optional Zink), never MobileGlues
     * - [GlRendererKind.MOBILE_GLUES]: LGPL opt-in — disguise MobileGlues as libgl4es_114.so
     * - Plugin GLES wrappers (Krypton / LTW): disguise plugin .so as libgl4es_114.so
     * - Mesa (Zink / VirGL / Freedreno): keep holy gl4es as fallback; LIBGL_NAME points at OSMesa
     */
    fun applyRenderer(kind: GlRendererKind) {
        applyRenderer(kind, profile = null)
    }

    fun applyRenderer(kind: GlRendererKind, profile: com.booxin.launcher.core.runtime.ModRenderProfile?) {
        val dest = nativesDir()
        val gl4 = File(dest, "libgl4es_114.so")
        val holy = File(dest, "libgl4es_holy.so")
        val mg = File(dest, "libmobileglues.so")
        when (kind) {
            GlRendererKind.GL4ES -> {
                stageHolyGl4es(dest, gl4, holy, mg)
            }
            GlRendererKind.BOOXIN_GLUES -> {
                val resolved = com.booxin.launcher.core.runtime.BooxinGlStack.resolve(dest, profile)
                android.util.Log.i("BooxinRuntime", resolved.note)
                when (resolved.engine) {
                    com.booxin.launcher.core.runtime.BooxinGlEngine.ZINK -> {
                        applyRenderer(GlRendererKind.VULKAN_ZINK, profile)
                        return
                    }
                    com.booxin.launcher.core.runtime.BooxinGlEngine.MOBILE_GLUES_COMPAT -> {
                        require(mg.isFile) { "最大兼容需要 MobileGlues: ${mg.absolutePath}" }
                        if (!holy.isFile && gl4.isFile && gl4.length() != mg.length()) {
                            gl4.copyTo(holy, overwrite = true)
                        }
                        mg.copyTo(gl4, overwrite = true)
                        android.util.Log.i(
                            "BooxinRuntime",
                            "最大兼容: staged MobileGlues as libgl4es_114.so (LGPL)"
                        )
                    }
                    com.booxin.launcher.core.runtime.BooxinGlEngine.ANGLE_EGL -> {
                        stageHolyGl4es(dest, gl4, holy, mg)
                        stageAngleLibs(dest)
                        val clean = File(dest, "libbooxingl.so")
                        if (clean.isFile) {
                            if (!holy.isFile) gl4.copyTo(holy, overwrite = true)
                            clean.copyTo(gl4, overwrite = true)
                        }
                        android.util.Log.i(
                            "BooxinRuntime",
                            "Path A: ANGLE EGL staged; libname=${gl4.name}"
                        )
                    }
                    com.booxin.launcher.core.runtime.BooxinGlEngine.CLEANROOM -> {
                        // Keep MIT holy GL4ES as backend; stage clean-room as LWJGL libname.
                        stageHolyGl4es(dest, gl4, holy, mg)
                        val clean = File(dest, "libbooxingl.so")
                        require(clean.isFile) { "缺少 clean-room libbooxingl.so" }
                        if (!holy.isFile) {
                            gl4.copyTo(holy, overwrite = true)
                        }
                        clean.copyTo(gl4, overwrite = true)
                        android.util.Log.i(
                            "BooxinRuntime",
                            "Path A M4: libbooxingl.so as libname, backend=${holy.name}"
                        )
                    }
                    com.booxin.launcher.core.runtime.BooxinGlEngine.GL4ES_MIT -> {
                        stageHolyGl4es(dest, gl4, holy, mg)
                    }
                }
            }
            GlRendererKind.MOBILE_GLUES -> {
                require(mg.isFile) { "缺少 MobileGlues (LGPL): ${mg.absolutePath}" }
                if (!holy.isFile && gl4.isFile && gl4.length() != mg.length()) {
                    gl4.copyTo(holy, overwrite = true)
                }
                mg.copyTo(gl4, overwrite = true)
            }
            GlRendererKind.KRYPTON, GlRendererKind.LTW -> {
                val pluginGl = com.booxin.launcher.core.runtime.RendererInstaller.glLibrary(kind)
                    ?: error("请先在设置中下载 ${kind.displayName}")
                if (!holy.isFile && gl4.isFile && (!mg.isFile || gl4.length() != mg.length())) {
                    gl4.copyTo(holy, overwrite = true)
                }
                pluginGl.copyTo(gl4, overwrite = true)
                // Keep a copy under the plugin soname in natives for dlopen-by-name.
                pluginGl.copyTo(File(dest, pluginGl.name), overwrite = true)
            }
            GlRendererKind.VULKAN_ZINK, GlRendererKind.VIRGL, GlRendererKind.FREEDRENO -> {
                // Restore holy gl4es so accidental GLES loads still resolve;
                // LaunchCommandBuilder points LIBGL_NAME at the Mesa/OSMesa lib.
                if (holy.isFile) {
                    holy.copyTo(gl4, overwrite = true)
                }
                val pluginDir = com.booxin.launcher.core.runtime.RendererInstaller.pluginNativeDir(kind)
                    ?: error("请先在设置中下载 ${kind.displayName}")
                pluginDir.listFiles()?.forEach { lib ->
                    if (lib.isFile && lib.name.endsWith(".so")) {
                        lib.copyTo(File(dest, lib.name), overwrite = true)
                    }
                }
            }
            GlRendererKind.ANGLE -> {
                stageHolyGl4es(dest, gl4, holy, mg)
                stageAngleLibs(dest)
            }
        }
        gl4.setReadable(true, false)
        gl4.setExecutable(true, false)
    }

    private fun stageAngleLibs(dest: File) {
        val pluginDir = com.booxin.launcher.core.runtime.RendererInstaller.pluginNativeDir(
            GlRendererKind.ANGLE
        )
        pluginDir?.listFiles()?.forEach { lib ->
            if (lib.isFile && lib.name.endsWith(".so")) {
                val out = File(dest, lib.name)
                lib.copyTo(out, overwrite = true)
                out.setReadable(true, false)
                out.setExecutable(true, false)
            }
        }
        val hasEgl = File(dest, "libEGL_angle.so").isFile || dest.listFiles()?.any {
            it.name.contains("EGL", ignoreCase = true) && it.name.contains("angle", ignoreCase = true)
        } == true
        require(hasEgl) { "请先在设置中下载 ANGLE（缺少 libEGL_angle.so）" }
    }

    /** Path A / GL4ES: stage MIT holy gl4es (never MobileGlues bytes). */
    private fun stageHolyGl4es(dest: File, gl4: File, holy: File, mg: File) {
        require(holy.isFile || gl4.isFile) {
            "缺少 holy gl4es (MIT): ${holy.absolutePath}"
        }
        val source = when {
            holy.isFile && (!mg.isFile || holy.length() != mg.length()) -> holy
            gl4.isFile && (!mg.isFile || gl4.length() != mg.length()) -> gl4
            holy.isFile -> holy
            else -> gl4
        }
        source.copyTo(gl4, overwrite = true)
        File(dest, "libbooxingl.so").takeIf { it.isFile }?.let {
            android.util.Log.i("BooxinRuntime", "clean-room helper present: ${it.name}")
        }
    }

    /** Map LWJGL soname aliases to staged .so filenames. */
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
        // 允许 APK 内任意 liblwjgl*。
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

    private fun extractNamedFromApk(
        context: Context,
        dest: File,
        abiFolder: String,
        name: String
    ): Boolean {
        val apk = File(context.applicationInfo.sourceDir)
        if (!apk.isFile) return false
        val entryName = "lib/$abiFolder/$name"
        return ZipFile(apk).use { zip ->
            val entry = zip.getEntry(entryName) ?: return@use false
            val out = File(dest, name)
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out.setReadable(true, false)
            out.setExecutable(true, false)
            true
        }
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
