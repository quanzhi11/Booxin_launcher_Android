package com.booxin.launcher.core.launch

import android.content.Context
import com.booxin.launcher.BuildConfig
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.GameJsonParser
import com.booxin.launcher.core.download.game.LibraryDownloadHelper
import com.booxin.launcher.core.download.game.LibraryFilter
import com.booxin.launcher.core.download.game.ResolvedLibrary
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.java.InstalledJavaRuntime
import com.booxin.launcher.core.java.MinecraftJavaRequirement
import com.booxin.launcher.core.runtime.RendererBackend
import com.booxin.launcher.core.runtime.RuntimeEnv
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.TimeZone
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

data class LaunchCommand(
    val javaBinary: File,
    val javaHome: File,
    val workingDir: File,
    val jvmArgs: List<String>,
    val mainClass: String,
    val gameArgs: List<String>,
    val classpath: List<File>,
    val env: Map<String, String>
) {
    fun asProcessCommand(): List<String> {
        return buildList {
            add(javaBinary.absolutePath)
            addAll(jvmArgs)
            add("-cp")
            add(classpath.joinToString(File.pathSeparator) { it.absolutePath })
            add(mainClass)
            addAll(gameArgs)
        }
    }

    fun summarize(): String {
        return buildString {
            appendLine("java=${javaBinary.absolutePath}")
            appendLine("cwd=${workingDir.absolutePath}")
            appendLine("main=$mainClass")
            appendLine("cp=${classpath.size} jars")
            if (classpath.size <= 16) {
                appendLine(
                    "cpJars=${classpath.joinToString(",") { it.name }}"
                )
            }
            if (classpath.size == 1 && classpath[0].name.contains("classpath", ignoreCase = true)) {
                appendLine("classpathJar=${classpath[0].absolutePath}")
            }
            appendLine("jvm=${jvmArgs.size} args")
            val clientJar = jvmArgs.firstOrNull { it.startsWith("-Dminecraft.client.jar=") }
                ?: jvmArgs.firstOrNull { it.startsWith("-Dfabric.gameJarPath=") }
            appendLine("clientJar=${clientJar ?: "MISSING"}")
            val legacyFile = jvmArgs.firstOrNull { it.startsWith("-DlegacyClassPath.file=") }
            if (legacyFile != null) {
                appendLine("legacyClassPathFile=${legacyFile.removePrefix("-DlegacyClassPath.file=")}")
            }
            val modulePath = jvmArgs.asSequence()
                .mapIndexedNotNull { index, arg ->
                    when {
                        arg == "-p" || arg == "--module-path" ->
                            jvmArgs.getOrNull(index + 1)
                        arg.startsWith("--module-path=") ->
                            arg.removePrefix("--module-path=")
                        arg.startsWith("-p=") ->
                            arg.removePrefix("-p=")
                        else -> null
                    }
                }
                .firstOrNull()
            // Fabric/Quilt 通常无 module-path。
            appendLine("modulePath=${modulePath?.let { "yes (${it.split(File.pathSeparator).size} entries)" } ?: "n/a"}")
            appendLine("ignoreList=${jvmArgs.firstOrNull { it.startsWith("-DignoreList=") } ?: "default"}")
            val agent = jvmArgs.firstOrNull { it.startsWith("-javaagent:") }
            appendLine("javaagent=${agent?.substringBefore('=')?.plus("=…") ?: "none"}")
            appendLine("game=${gameArgs.joinToString(" ")}")
        }
    }
}

/** Builds the Minecraft client argv / env for Android. */
class LaunchCommandBuilder(
    private val context: Context
) {
    companion object {
        /** Brand shown as --versionType / ${launcher_name}; keep stable for mods & F3. */
        const val LAUNCHER_BRAND = "Booxin_launcher"
    }

    fun build(
        versionId: String,
        username: String,
        java: InstalledJavaRuntime,
        maxMemoryMb: Int = 1024,
        windowWidth: Int = 854,
        windowHeight: Int = 480,
        uuid: String? = null,
        accessToken: String? = null,
        userType: String? = null,
        serverAddress: String? = null,
        /** Optional -javaagent:... for vanilla offline skins (authlib-injector). */
        javaAgentArg: String? = null,
        /** HMCL/FCL-compatible auth when [javaAgentArg] is set. */
        offlineSkinAccessToken: String? = null,
        offlineSkinUserType: String? = null,
        offlineSkinExtraJvmArgs: List<String> = emptyList(),
        /** When set, overrides launcher prefs for EGL FORCE_VSYNC. */
        forceVsync: Boolean? = null
    ): LaunchCommand {
        val versionRoot = File(LauncherPaths.versionsDir, versionId)
        val jsonFile = File(versionRoot, "$versionId.json")
        require(jsonFile.exists()) { "缺少 version.json: ${jsonFile.absolutePath}" }

        // Repair older Fabric/Quilt installs that omit "jar": "<mcVersion>".
        VersionJsonMerger.ensureInheritedJarField(versionId)

        val root = VersionJsonMerger.merge(versionId)
            ?: error("无法合并 version.json: $versionId")
        val jarFile = VersionJsonMerger.resolveClientJar(versionId)
            ?: error("缺少客户端 jar: $versionId")
        require(jarFile.isFile) { "缺少客户端 jar: ${jarFile.absolutePath}" }

        val mainClass = root.optString("mainClass").ifBlank {
            error("version.json 缺少 mainClass")
        }
        val mcVersionId = VersionJsonMerger.resolveMinecraftVersionId(versionId)
        val assetIndexId = root.optJSONObject("assetIndex")?.optString("id")
            ?: root.optString("assets").ifBlank { "legacy" }

        AndroidGameRuntime.ensure(context)
        val modProfile = RendererBackend.lastProfile(versionId)
        val requestedRenderer = RendererBackend.kindForLaunch(versionId, mcVersionId)
        val renderer = resolveRendererOrFallback(
            requestedRenderer,
            mcVersionId,
            if (requestedRenderer == GlRendererKind.BOOXIN_GLUES) {
                com.booxin.launcher.core.runtime.BooxinGlStack.fallbackChain(modProfile)
            } else {
                modProfile.fallback
            }
        )
        if (renderer == GlRendererKind.REL) {
            // Optional: re-extract from adb-pushed APK for QA; bundled librel.so is default.
            com.booxin.launcher.core.runtime.RendererInstaller.refreshRelFromSideloadIfPresent()
        }
        // Max compat / Path A: heavy → Zink; then ANGLE; then MobileGlues (LGPL) if still needed.
        if (renderer == GlRendererKind.BOOXIN_GLUES && modProfile.isHeavyGl) {
            if (!com.booxin.launcher.core.runtime.RendererInstaller.isInstalled(GlRendererKind.VULKAN_ZINK)) {
                kotlinx.coroutines.runBlocking {
                    com.booxin.launcher.core.runtime.RendererInstaller.ensureInstalled(
                        GlRendererKind.VULKAN_ZINK
                    )
                }
            }
            if ("shaders" in modProfile.features &&
                !com.booxin.launcher.core.runtime.RendererInstaller.isInstalled(GlRendererKind.VULKAN_ZINK) &&
                !com.booxin.launcher.core.runtime.RendererInstaller.isInstalled(GlRendererKind.ANGLE)
            ) {
                kotlinx.coroutines.runBlocking {
                    com.booxin.launcher.core.runtime.RendererInstaller.ensureInstalled(
                        GlRendererKind.ANGLE
                    )
                }
            }
            val maxCompat = runCatching {
                com.booxin.launcher.core.LauncherPrefs.isMaxGlCompat()
            }.getOrDefault(true)
            val hasCleanroom = File(AndroidGameRuntime.nativesDir(), "libbooxingl.so").isFile
            if (maxCompat &&
                !hasCleanroom &&
                !com.booxin.launcher.core.runtime.RendererInstaller.isInstalled(GlRendererKind.VULKAN_ZINK) &&
                !com.booxin.launcher.core.runtime.RendererInstaller.isInstalled(GlRendererKind.MOBILE_GLUES)
            ) {
                kotlinx.coroutines.runBlocking {
                    com.booxin.launcher.core.runtime.RendererInstaller.ensureInstalled(
                        GlRendererKind.MOBILE_GLUES
                    )
                }
            }
        }
        val pathA = if (renderer == GlRendererKind.BOOXIN_GLUES) {
            com.booxin.launcher.core.runtime.BooxinGlStack.resolve(
                AndroidGameRuntime.nativesDir(),
                modProfile
            )
        } else {
            null
        }
        AndroidGameRuntime.applyRenderer(renderer, modProfile)
        // Effective GL lib kind (Zink staging under BooxinGlues product name).
        val glKind = when (pathA?.engine) {
            com.booxin.launcher.core.runtime.BooxinGlEngine.ZINK -> GlRendererKind.VULKAN_ZINK
            com.booxin.launcher.core.runtime.BooxinGlEngine.MOBILE_GLUES_COMPAT ->
                GlRendererKind.MOBILE_GLUES
            else -> renderer
        }
        val eglOverride = when (pathA?.engine) {
            com.booxin.launcher.core.runtime.BooxinGlEngine.ANGLE_EGL -> {
                val angleEgl = File(AndroidGameRuntime.nativesDir(), "libEGL_angle.so")
                if (angleEgl.isFile) angleEgl.absolutePath else "libEGL_angle.so"
            }
            com.booxin.launcher.core.runtime.BooxinGlEngine.MOBILE_GLUES_COMPAT ->
                File(AndroidGameRuntime.nativesDir(), "libmobileglues.so").let {
                    if (it.isFile) it.absolutePath else "libmobileglues.so"
                }
            else -> null
        }
        android.util.Log.i(
            "LaunchCmd",
            "renderer=${renderer.displayName} glKind=${glKind.displayName} " +
                "pathA=${pathA?.engine} profile=${modProfile.profileId} " +
                "matched=${modProfile.matchedMods.take(8)}"
        )
        val androidLwjgl = AndroidGameRuntime.lwjglJarForJava(java.majorVersion)
        require(androidLwjgl.isFile) {
            val hint = if (MinecraftJavaRequirement.needsJava8Lwjgl(java.majorVersion)) {
                "lwjgl-java8.jar"
            } else {
                "lwjgl.jar"
            }
            "缺少 Android LWJGL ($hint): ${androidLwjgl.absolutePath}"
        }
        if (MinecraftJavaRequirement.needsJava8Lwjgl(java.majorVersion)) {
            android.util.Log.i(
                "LaunchCmd",
                "Java ${java.majorVersion} → LWJGL java8 jar (${androidLwjgl.name})"
            )
        }

        val isForgeOrLoader = VersionJsonMerger.isModLoaderVersion(versionId)
        val needsSdl = MinecraftJavaRequirement.usesSdlWindowing(mcVersionId)
        // Peek mainClass early for legacy LWJGL2 / AWT Frame path (b1.8.1 etc.).
        val legacyLwjgl = MinecraftJavaRequirement.usesLegacyLwjglWindowing(mcVersionId, mainClass)

        val classpath = linkedSetOf<File>()
        val missingLibs = mutableListOf<String>()
        // Pre-1.13: Cacio goes on -Xbootclasspath/p (not regular -cp) — see jvmArgsWithAwt.
        val cacioBootJars = if (legacyLwjgl) {
            val cacio = AndroidGameRuntime.cacioBootJars()
            require(cacio.isNotEmpty()) {
                "远古版需要 Cacio AWT: ${AndroidGameRuntime.cacioDir().absolutePath}"
            }
            android.util.Log.i(
                "LaunchCmd",
                "legacy LWJGL2/AWT → Cacio bootclasspath/p jars=${cacio.joinToString { it.name }}"
            )
            cacio
        } else {
            emptyList()
        }
        // SDL (26.3+): thin 3.4 baseline JNI.class first (native invokePZ(IJJ)Z…).
        // Fat Android lwjgl.jar is 3.3.x and lacks those overloads → NoSuchMethodError.
        // Do NOT prepend full lwjgl-core-3.4 (Java25 FFM multi-release).
        if (needsSdl) {
            val jniShim = AndroidGameRuntime.lwjglJniSdlShimJar()
            require(jniShim.isFile) {
                "Minecraft $mcVersionId 需要 LWJGL JNI shim: ${jniShim.absolutePath}"
            }
            classpath += jniShim
        }
        classpath += androidLwjgl
        if (needsSdl) {
            val sdlJar = AndroidGameRuntime.lwjglSdlJar()
            require(sdlJar.isFile) {
                "Minecraft $mcVersionId 需要 LWJGL SDL 绑定（BSD）: ${sdlJar.absolutePath}"
            }
            classpath += sdlJar
        }
        // Must use the same LibraryFilter.upgrade() paths as download, otherwise
        // oshi 6.2→6.3 / oshi-common / jna-platform replacements are missing at launch
        // (Forge then surfaces NoClassDefFoundError: oshi/util/tuples/Quartet).
        val resolvedLibraries = resolveClasspathLibraries(
            root.optJSONArray("libraries") ?: JSONArray(),
            isForgeOrLoader
        )
        // Upgrades/injections (e.g. jna-platform:5.13.0) may not be on disk yet for
        // already-installed versions — fetch them before building the classpath.
        ensureResolvedLibrariesPresent(resolvedLibraries, versionId)
        var jnaJarVersion: String? = null
        for (lib in resolvedLibraries) {
            if (lib.name.startsWith("net.java.dev.jna:jna:") &&
                !lib.name.startsWith("net.java.dev.jna:jna-platform:")
            ) {
                jnaJarVersion = lib.name.split(':').getOrNull(2)
            }
            val file = resolveLibraryFile(lib)
            if (file != null) {
                classpath += file
            } else {
                missingLibs += lib.name
            }
        }
        classpath += jarFile
        var existingClasspath = classpath.filter { it.isFile && it.length() > 0L }
        val jnaBootPath = AndroidGameRuntime.jnaBootLibraryPath(jnaJarVersion)
        require(existingClasspath.isNotEmpty()) { "classpath 为空，缺少可用 jar" }
        require(jarFile.isFile && jarFile.length() > 0L) {
            "缺少客户端 jar（Fabric/Forge 需继承原版 jar）: ${jarFile.absolutePath}"
        }

        val isForgeBootstrap = ForgeBootstrapClasspathHelper.isForgeBootstrapMainClass(mainClass)
        if (isForgeBootstrap) {
            // Modern Forge (1.20.3+ / 1.21 / 26.x): curated -cp from shim + forge-client.
            // Dumping all libraries breaks JPMS → Optional.get No value present (Huawei).
            val curated = ForgeBootstrapClasspathHelper.buildClasspath(
                libraries = resolvedLibraries,
                versionId = versionId
            ) ?: error(
                "ForgeBootstrap 启动失败：缺少 shim/client 库。请重新安装对应 Forge 版本后再试。"
            )
            val prefix = existingClasspath.filter { file ->
                val n = file.name.lowercase(Locale.US)
                n.contains("lwjgl") || n.contains("sdl") || n.contains("jni") ||
                    n.contains("booxin") || file.absolutePath.contains("android-lwjgl", true)
            }
            existingClasspath = (prefix + curated).distinctBy { file ->
                runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            }
            android.util.Log.i(
                "LaunchCmd",
                "ForgeBootstrap curated -cp: prefix=${prefix.size} curated=${curated.size} " +
                    "total=${existingClasspath.size}"
            )
        }
        // Fabric Knot fails with "couldn't locate the game" when deps are incomplete.
        if (missingLibs.isNotEmpty()) {
            val critical = missingLibs.filter {
                it.contains("fabric-loader", ignoreCase = true) ||
                    it.contains("intermediary", ignoreCase = true) ||
                    it.contains("sponge-mixin", ignoreCase = true) ||
                    it.contains(":mixin:", ignoreCase = true) ||
                    it.contains("oshi-core", ignoreCase = true) ||
                    it.contains("oshi-common", ignoreCase = true) ||
                    it.startsWith("net.java.dev.jna:jna")
            }
            require(critical.isEmpty()) {
                "缺少关键依赖库，请重新安装该版本: ${critical.take(8).joinToString()}"
            }
        }

        val gameDir = versionRoot
        val assetsDir = LauncherPaths.assetsDir
        val resolvedUuid = uuid?.replace("-", "")?.ifBlank { null }
            ?: OfflineAuth.uuidNoDash(username)
        val skinAuthActive = !javaAgentArg.isNullOrBlank()
        // Plain offline → legacy + token 0; authlib-injector skin → msa + random token (HMCL/FCL).
        val resolvedToken = when {
            skinAuthActive && !offlineSkinAccessToken.isNullOrBlank() -> offlineSkinAccessToken
            else -> accessToken?.ifBlank { null } ?: "0"
        }
        val resolvedUserType = when {
            skinAuthActive && !offlineSkinUserType.isNullOrBlank() -> offlineSkinUserType
            resolvedToken == "0" -> "legacy"
            !userType.isNullOrBlank() -> userType
            else -> "msa"
        }
        val userProperties = "{}"

        val tokens = mapOf(
            "auth_player_name" to username,
            "version_name" to versionId,
            "game_directory" to gameDir.absolutePath,
            "assets_root" to assetsDir.absolutePath,
            "assets_index_name" to assetIndexId,
            "auth_uuid" to resolvedUuid,
            "auth_access_token" to resolvedToken,
            "user_type" to resolvedUserType,
            // Shown in title / F3 as brand; Create etc. also surface this string.
            "version_type" to LAUNCHER_BRAND,
            "user_properties" to userProperties,
            // Legacy ${auth_session}: offline clients expect "-" (not "0").
            "auth_session" to if (resolvedToken == "0") "-" else resolvedToken,
            "game_assets" to File(assetsDir, "virtual/$assetIndexId").absolutePath,
            "natives_directory" to AndroidGameRuntime.nativesDir().absolutePath,
            "launcher_name" to LAUNCHER_BRAND,
            "launcher_version" to BuildConfig.VERSION_NAME,
            "classpath" to existingClasspath.joinToString(File.pathSeparator) { it.absolutePath },
            "resolution_width" to windowWidth.toString(),
            "resolution_height" to windowHeight.toString(),
            "library_directory" to LauncherPaths.librariesDir.absolutePath,
            "classpath_separator" to File.pathSeparator,
            "primary_jar" to jarFile.absolutePath,
            // Forge ignoreList may reference ${primary_jar_name} so vanilla jar
            // is not turned into a second JPMS module next to forge-*-client.jar.
            "primary_jar_name" to jarFile.name,
            "language" to "zh_cn"
        )

        val isKnotLoader = isKnotMainClass(mainClass)
        val isOptiFine = isOptiFineVersion(versionId, mainClass, root)
        val isBootstrap = usesBootstrapLauncher(mainClass)
        // Shrink -cp on sensitive OEMs (vivo / Huawei HarmonyOS).
        // Do NOT use classpath.jar for Fabric/Quilt Knot: Manifest Class-Path
        // loads fabric-loader into AppClassLoader and Knot then crashes with
        // "trying to load FabricLoaderImpl from target class loader".
        // ForgeBootstrap already has a curated -cp — do not collapse/shorten further.
        val useForgeShortCp =
            !isForgeBootstrap &&
                isBootstrap &&
                isForgeOrLoader &&
                OemLaunchProfile.needsForgeShortClasspath()
        val useClasspathJar =
            !isForgeBootstrap &&
                !useForgeShortCp &&
                !isKnotLoader &&
                existingClasspath.size >= 8 &&
                OemLaunchProfile.needsClasspathJar()
        val launchClasspath: List<File>
        val forgeLegacyClasspathFile: File?
        if (useForgeShortCp) {
            val bootOnly = existingClasspath.filter {
                it.name.contains("bootstraplauncher", ignoreCase = true)
            }
            launchClasspath = if (bootOnly.isNotEmpty()) bootOnly else existingClasspath
            forgeLegacyClasspathFile = File(context.cacheDir, "forge-legacy-classpath.txt").also { f ->
                f.writeText(existingClasspath.joinToString("\n") { it.absolutePath } + "\n")
            }
            android.util.Log.i(
                "LaunchCmd",
                "OEM Forge short -cp: device=${OemLaunchProfile.describe()} " +
                    "bootJars=${launchClasspath.size} legacyLines=${existingClasspath.size}"
            )
        } else if (useClasspathJar) {
            val cpJar = File(context.cacheDir, "booxin-classpath-$versionId.jar")
            writeClasspathJar(existingClasspath, cpJar)
            launchClasspath = listOf(cpJar)
            forgeLegacyClasspathFile = null
            android.util.Log.i(
                "LaunchCmd",
                "OEM classpath.jar: device=${OemLaunchProfile.describe()} " +
                    "entries=${existingClasspath.size} jar=${cpJar.absolutePath}"
            )
        } else {
            launchClasspath = existingClasspath
            forgeLegacyClasspathFile = null
            if (isKnotLoader) {
                android.util.Log.i(
                    "LaunchCmd",
                    "Knot full -cp: jars=${existingClasspath.size} " +
                        "(classpath.jar skipped — Fabric loader isolation)"
                )
            }
        }
        val launchClasspathString =
            launchClasspath.joinToString(File.pathSeparator) { it.absolutePath }
        if (isForgeOrLoader && !isKnotLoader && !isOptiFine) {
            // Disable Forge splash (second window).
            disableForgeSplash(gameDir)
        }
        val jvmArgs = when {
            isKnotLoader -> buildKnotJvmArgs(
                jarFile = jarFile,
                gameDir = gameDir,
                maxMemoryMb = maxMemoryMb,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                javaHome = java.homeDir,
                javaMajor = java.majorVersion,
                classpath = launchClasspathString,
                renderer = glKind,
                versionRoot = root,
                tokens = tokens,
                androidLwjgl = androidLwjgl,
                mainClass = mainClass,
                jnaBootPath = jnaBootPath,
                sdlWindowing = needsSdl
            )
            isOptiFine -> buildOptiFineJvmArgs(
                jarFile = jarFile,
                gameDir = gameDir,
                maxMemoryMb = maxMemoryMb,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                javaHome = java.homeDir,
                javaMajor = java.majorVersion,
                classpath = launchClasspathString,
                renderer = glKind,
                versionRoot = root,
                tokens = tokens,
                jnaBootPath = jnaBootPath,
                sdlWindowing = needsSdl
            )
            isForgeOrLoader -> buildForgeJvmArgs(
                jarFile = jarFile,
                gameDir = gameDir,
                maxMemoryMb = maxMemoryMb,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                javaHome = java.homeDir,
                javaMajor = java.majorVersion,
                classpath = launchClasspathString,
                renderer = glKind,
                mainClass = mainClass,
                versionRoot = root,
                tokens = tokens,
                mcVersionId = mcVersionId,
                jnaBootPath = jnaBootPath,
                legacyClasspathFile = forgeLegacyClasspathFile,
                legacyClasspathEntryCount = existingClasspath.size,
                sdlWindowing = needsSdl
            )
            else -> buildVanillaJvmArgs(
                jarFile = jarFile,
                gameDir = gameDir,
                maxMemoryMb = maxMemoryMb,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                javaHome = java.homeDir,
                javaMajor = java.majorVersion,
                classpath = launchClasspathString,
                renderer = glKind,
                jnaBootPath = jnaBootPath,
                sdlWindowing = needsSdl
            )
        }

        val jvmArgsWithAwt = if (legacyLwjgl) {
            // Match Pojav Tools.getCacioJavaArgs(java8=true): properties + bootclasspath/p.
            val boot = cacioBootJars.joinToString(File.pathSeparator) { it.absolutePath }
            jvmArgs + listOf(
                "-Djava.awt.headless=false",
                "-Dcacio.managed.screensize=${windowWidth}x${windowHeight}",
                "-Dcacio.font.fontmanager=sun.awt.X11FontManager",
                "-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler",
                "-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel",
                "-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit",
                "-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment",
                "-Xbootclasspath/p:$boot"
            )
        } else {
            jvmArgs
        }

        val rawGameArgs = buildGameArgs(root, tokens)
        // LaunchWrapper 1.5 OptionParser only knows gameDir/assetsDir/tweakClass/version —
        // injecting --versionType throws UnrecognizedOptionException ("Unable to launch").
        val gameArgs = forceVersionType(rawGameArgs, LAUNCHER_BRAND, root)
            .let { appendServerArgs(it, serverAddress, mcVersionId) }

        val stagedNativesDir = AndroidGameRuntime.nativesDir()
        val stagedNatives = stagedNativesDir.absolutePath
        val glLib = RuntimeEnv.glLibraryFile(stagedNativesDir, glKind)
        require(glLib.isFile) { "缺少渲染库: ${glLib.absolutePath}" }
        // REL: host EGL = system. MG (FCL-compat): LIBGL_EGL = libmobileglues itself.
        val libGlEgl = when {
            glKind == GlRendererKind.REL -> "libEGL.so"
            glKind == GlRendererKind.MOBILE_GLUES -> glLib.absolutePath
            eglOverride != null -> eglOverride
            else -> RuntimeEnv.eglLib(glKind)
        }
        val bridgeEgl = when {
            glKind == GlRendererKind.REL || glKind == GlRendererKind.MOBILE_GLUES ->
                glLib.absolutePath
            else -> libGlEgl
        }
        val pluginDir = com.booxin.launcher.core.runtime.RendererInstaller.pluginNativeDir(glKind)
        val libraryPath = buildLibraryPath(
            java.homeDir,
            stagedNatives,
            pluginDir?.absolutePath
        )
        val envBase = linkedMapOf(
            "JAVA_HOME" to java.homeDir.absolutePath,
            "HOME" to gameDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "PATH" to "${File(java.homeDir, "bin").absolutePath}:${System.getenv("PATH").orEmpty()}",
            "LD_LIBRARY_PATH" to libraryPath,
            "LIBGL_ES" to RuntimeEnv.libGlEs(glKind),
            "LIBGL_NAME" to glLib.absolutePath,
            // Use effective glKind (Path A may stage MobileGlues under BooxinGlues).
            "LIBGL_STRING" to RuntimeEnv.libGlString(glKind),
            "LIBGL_EGL" to libGlEgl,
            "LIBGL_NOERROR" to "1",
            "LIBGL_MIPMAP" to "3",
            "LIBGL_NOINTOVLHACK" to "1",
            "LIBGL_NORMALIZE" to "1",
            "FORCE_VSYNC" to BooxinLaunchTune.forceVsyncEnv(
                forceVsync ?: com.booxin.launcher.core.LauncherPrefs.enableVsync()
            ),
            "AWTSTUB_WIDTH" to windowWidth.toString(),
            "AWTSTUB_HEIGHT" to windowHeight.toString(),
            "MESA_GLSL_CACHE_DIR" to context.cacheDir.absolutePath
        )
        com.booxin.launcher.core.runtime.BooxinGluesEnv.apply(
            env = envBase,
            context = context,
            kind = glKind,
            profile = modProfile,
            resolved = pathA,
            mcVersionId = mcVersionId
        )
        RuntimeEnv.pluginExtraEnv(glKind).forEach { (k, v) -> envBase[k] = v }
        if (glKind == GlRendererKind.MOBILE_GLUES) {
            // Match FCL/Zalith: LIBGL_EGL=POJAVEXEC_EGL=libmobileglues.so
            envBase["LIBGL_EGL"] = glLib.absolutePath
        }
        if (glKind == GlRendererKind.MCRENDER) {
            // Writable caches used by libmcrender (shader dump / disk cache).
            val mcCache = File(context.cacheDir, "mcrender").also { it.mkdirs() }
            envBase.putIfAbsent("MCRENDER_CACHE_DIR", mcCache.absolutePath)
            envBase.putIfAbsent("MCRENDER_SHADER_DUMP_DIR", mcCache.absolutePath)
            // Same soname the bridge uses — avoid a second libEGL mapping.
            envBase["LIBGL_EGL"] = "libEGL.so"
            com.booxin.launcher.core.runtime.McRenderConfig.ensureForLaunch(context, glKind)
        }
        if (MinecraftJavaRequirement.usesSdlWindowing(mcVersionId)) {
            envBase["BOOXIN_WINDOWING"] = "sdl"
            val sdl3 = File(stagedNativesDir, "libSDL3.so")
            require(sdl3.isFile) {
                "Minecraft $mcVersionId 需要 SDL3（zlib）。缺少 ${sdl3.absolutePath}"
            }
            envBase["SDL_VIDEODRIVER"] = "android"
            envBase["BOOXIN_SDL3_LIB"] = sdl3.absolutePath
            // Android SDL uses system EGL → force ES. LWJGL still loads MobileGlues;
            // we sync eglMakeCurrent into MG after SDL CreateContext/MakeCurrent.
            envBase["SDL_OPENGL_ES_DRIVER"] = "1"
            envBase["SDL_VIDEO_FORCE_EGL"] = "1"
            envBase["SDL_OPENGL_LIBRARY"] = glLib.absolutePath
            envBase["SDL_EGL_LIBRARY"] = if (glKind == GlRendererKind.REL) glLib.absolutePath else libGlEgl
            // Help SDL Android find the app (official zlib path; still no SDLActivity yet).
            envBase["SDL_ANDROID_APK_EXPANSION_MAIN_FILE_VERSION"] = "1"
            // ColorOS SKIP_GLFW must not trigger early HotSpot System.load on SDL path either.
            envBase["BOOXIN_SKIP_GLFW_PREINIT"] = "1"
        }
        if (legacyLwjgl) {
            // LWJGL2 Display path — GLFW.<clinit> is unnecessary and can fail on Java 8.
            envBase["BOOXIN_SKIP_GLFW_PREINIT"] = "1"
            // Keep POJAV_RENDERER visible to Java for lwjglx only (not ColorOS SKIP_GLFW).
            envBase["BOOXIN_KEEP_JAVA_POJAV_RENDERER"] = "1"
            // jre_launcher: early System.load(bridge)+awt only for true LWJGL2.
            envBase["BOOXIN_LEGACY_LWJGL2"] = "1"
        }
        val env = RuntimeEnv.withNativeAliases(
            envBase,
            stagedNatives,
            glKind,
            eglOverride = bridgeEgl
        ).toMutableMap()
        if (legacyLwjgl) {
            // lwjglx mglfwCreateWindow: getenv("POJAV_RENDERER").equals(...) — null → NPE.
            // Set AFTER withNativeAliases (which intentionally omits POJAV_RENDERER for modern).
            val token = env[RuntimeEnv.RENDERER] ?: RuntimeEnv.rendererToken(glKind)
            env[RuntimeEnv.LEGACY_POJAV_RENDERER] =
                if (token.contains("opengles3_rel")) "opengles3" else token
        }

        val injectorArg = InjectorMapResolver.resolveArg(context, versionId, mcVersionId)
        var finalJvmArgs = if (injectorArg.isNullOrBlank()) {
            jvmArgsWithAwt
        } else {
            jvmArgsWithAwt + "-Dbooxin.injector=$injectorArg"
        }
        // javaagent must be a VM option early; enables vanilla offline skins.
        val agent = javaAgentArg?.takeIf { it.startsWith("-javaagent:") }
        if (!agent.isNullOrBlank()) {
            finalJvmArgs = listOf(agent) + offlineSkinExtraJvmArgs + finalJvmArgs
        }

        return LaunchCommand(
            javaBinary = java.javaBinary,
            javaHome = java.homeDir,
            // ForgeBootstrap shim resolves libraries/ relative to process cwd.
            workingDir = if (isForgeBootstrap) {
                ForgeBootstrapClasspathHelper.resolveLibrariesRoot()
            } else {
                gameDir
            },
            jvmArgs = finalJvmArgs,
            mainClass = mainClass,
            gameArgs = gameArgs,
            classpath = launchClasspath,
            env = env
        )
    }

    /**
     * Empty jar whose Manifest Class-Path lists every real library.
     * Shrinks -Djava.class.path for OEM CreateJavaVM stalls (vivo).
     */
    private fun writeClasspathJar(jars: List<File>, outFile: File) {
        val manifest = Manifest()
        val attrs = manifest.mainAttributes
        attrs[Attributes.Name.MANIFEST_VERSION] = "1.0"
        // Absolute file: URLs — relative Class-Path would resolve against cacheDir.
        attrs[Attributes.Name.CLASS_PATH] = jars.joinToString(" ") { jar ->
            jar.toURI().toURL().toExternalForm()
        }
        outFile.parentFile?.mkdirs()
        JarOutputStream(FileOutputStream(outFile), manifest).use { jos ->
            // Manifest-only jar; Class-Path entries are loaded by the system loader.
            jos.flush()
        }
        require(outFile.isFile && outFile.length() > 0L) {
            "classpath.jar 写入失败: ${outFile.absolutePath}"
        }
    }

    /** Vanilla JVM args (no Forge module-path). */
    private fun resolveRendererOrFallback(
        requested: GlRendererKind,
        mcVersionId: String,
        profileFallback: List<GlRendererKind> = emptyList()
    ): GlRendererKind {
        if (!requested.requiresPlugin) return requested
        if (com.booxin.launcher.core.runtime.RendererInstaller.isInstalled(requested)) {
            return requested
        }
        val installed = kotlinx.coroutines.runBlocking {
            com.booxin.launcher.core.runtime.RendererInstaller.ensureInstalled(requested)
        }
        if (installed.isSuccess) return requested
        val err = installed.exceptionOrNull()?.message ?: "unknown"
        val chain = (profileFallback + listOf(
            GlRendererKind.MOBILE_GLUES,
            GlRendererKind.BOOXIN_GLUES,
            GlRendererProfile.forVersion(mcVersionId)
        )).distinct()
        val fallback = chain.firstOrNull { candidate ->
            !candidate.requiresPlugin ||
                com.booxin.launcher.core.runtime.RendererInstaller.isInstalled(candidate)
        } ?: GlRendererKind.MOBILE_GLUES
        android.util.Log.w(
            "LaunchCmd",
            "渲染器 ${requested.displayName} 下载失败，回退 ${fallback.displayName}: $err"
        )
        // Surface the reason in env so GameLaunchService log line still shows it.
        System.setProperty(
            "booxin.renderer.fallback",
            "${requested.displayName}→${fallback.displayName} ($err)"
        )
        return fallback
    }

    private fun buildVanillaJvmArgs(
        jarFile: File,
        gameDir: File,
        maxMemoryMb: Int,
        windowWidth: Int,
        windowHeight: Int,
        javaHome: File,
        javaMajor: Int,
        classpath: String,
        renderer: GlRendererKind,
        jnaBootPath: String,
        sdlWindowing: Boolean = false
    ): List<String> {
        return buildCommonAndroidJvmArgs(
            jarFile = jarFile,
            gameDir = gameDir,
            maxMemoryMb = maxMemoryMb,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            javaHome = javaHome,
            javaMajor = javaMajor,
            classpath = classpath,
            renderer = renderer,
            forgeExtras = false,
            jnaBootPath = jnaBootPath,
            sdlWindowing = sdlWindowing
        )
    }

    private fun ensureResolvedLibrariesPresent(
        libraries: List<ResolvedLibrary>,
        versionId: String
    ) {
        val missing = libraries.filter { lib ->
            lib.url.isNotBlank() && resolveLibraryFile(lib) == null
        }
        if (missing.isEmpty()) return
        runBlocking {
            LibraryDownloadHelper().downloadResolvedLibraries(missing, versionId)
        }
    }

    /** Exact upgraded path, else newest on-disk jar for the same group:artifact. */
    private fun resolveLibraryFile(lib: ResolvedLibrary): File? {
        val exact = File(LauncherPaths.librariesDir, lib.path)
        if (exact.isFile && exact.length() > 0L) return exact
        return findLocalArtifact(lib.name)
    }

    private fun findLocalArtifact(mavenName: String): File? {
        val parts = mavenName.substringBefore('@').split(':')
        if (parts.size < 3) return null
        val groupPath = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val exactDir = File(LauncherPaths.librariesDir, "$groupPath/$artifact/$version")
        if (exactDir.isDirectory) {
            exactDir.listFiles()
                ?.firstOrNull { it.isFile && it.extension == "jar" && it.length() > 0L }
                ?.let { return it }
        }
        val dir = File(LauncherPaths.librariesDir, "$groupPath/$artifact")
        if (!dir.isDirectory) return null
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "jar" && it.length() > 0L }
            .filter { it.name.startsWith("$artifact-") }
            // Prefer higher version directory names (…/5.17.0/jna-platform-5.17.0.jar).
            .maxWithOrNull(compareBy({ it.parentFile?.name.orEmpty() }, { it.name }))
    }

    /**
     * Apply [LibraryFilter] the same way download does, so upgraded oshi/jna jars
     * resolve to the files actually on disk.
     */
    private fun resolveClasspathLibraries(
        libraries: JSONArray,
        isForgeOrLoader: Boolean
    ): List<ResolvedLibrary> {
        val raw = ArrayList<ResolvedLibrary>()
        for (i in 0 until libraries.length()) {
            val lib = libraries.getJSONObject(i)
            val name = lib.getString("name")
            if (!LibraryFilter.shouldKeep(name)) continue
            // earlydisplay opens a second GLFW window and races our Surface → crash.
            // Progress is shown on the Android loading overlay instead.
            // Forge: net.minecraftforge:fmlearlydisplay:…
            // NeoForge: net.neoforged.fml:earlydisplay:… (takeOverGlfwWindow NPE on stub)
            if (isForgeOrLoader && isEarlyDisplayLibrary(name)) continue
            val artifact = lib.optJSONObject("downloads")?.optJSONObject("artifact")
            if (artifact == null && lib.has("natives")) continue
            if (!rulesAllow(lib.optJSONArray("rules"))) continue
            val path = artifact?.optString("path")?.ifBlank { null }
                ?: GameJsonParser.mavenPath(name)
            val url = when {
                artifact?.has("url") == true -> artifact.getString("url").ifBlank { "" }
                lib.has("url") -> lib.getString("url").let { base ->
                    if (base.isBlank()) ""
                    else if (base.endsWith("/")) base + path else "$base/$path"
                }
                else -> GameJsonParser.DEFAULT_LIBRARY_URL + path
            }
            raw += ResolvedLibrary(
                name = name,
                path = path,
                url = url,
                sha1 = artifact?.optString("sha1")?.ifBlank { null },
                size = artifact?.optLong("size", 0L) ?: 0L
            )
        }
        return LibraryFilter.upgrade(raw)
    }

    /**
     * Fabric/Quilt Knot: keep Android LWJGL on the app (system) classloader.
     * HotSpot preinit already System.loads the bridge/LWJGL via AppClassLoader;
     * if Knot reloads org.lwjgl.* it hits "already loaded in another classloader".
     *
     * Fabric/Quilt 属性都写上。
     */
    private fun buildKnotJvmArgs(
        jarFile: File,
        gameDir: File,
        maxMemoryMb: Int,
        windowWidth: Int,
        windowHeight: Int,
        javaHome: File,
        javaMajor: Int,
        classpath: String,
        renderer: GlRendererKind,
        versionRoot: JSONObject,
        tokens: Map<String, String>,
        androidLwjgl: File,
        mainClass: String,
        jnaBootPath: String,
        sdlWindowing: Boolean = false
    ): List<String> {
        val nativeDir = AndroidGameRuntime.nativesDir().absolutePath
        val versionJvm = parseVersionJvmArgs(versionRoot, tokens, nativeDir)
        return buildList {
            addAll(
                buildCommonAndroidJvmArgs(
                    jarFile = jarFile,
                    gameDir = gameDir,
                    maxMemoryMb = maxMemoryMb,
                    windowWidth = windowWidth,
                    windowHeight = windowHeight,
                    javaHome = javaHome,
                    javaMajor = javaMajor,
                    classpath = classpath,
                    renderer = renderer,
                    forgeExtras = false,
                    jnaBootPath = jnaBootPath,
                    sdlWindowing = sdlWindowing
                )
            )
            addAll(versionJvm)
            // Point Knot at the real vanilla client jar (often parent of fabric-* id).
            add("-Dfabric.gameJarPath=${jarFile.absolutePath}")
            add("-Dloader.gameJarPath=${jarFile.absolutePath}")
            // Fabric props — keep Android LWJGL on system classloader.
            add("-Dfabric.systemLibraries=${androidLwjgl.absolutePath}")
            add("-Dfabric.noGui=true")
            // Quilt props (Fabric ignores unknown loader.* keys)
            add("-Dloader.systemLibraries=${androidLwjgl.absolutePath}")
            add("-Dloader.noGui=true")
            // Help more mods: don't bail on "unknown" OS; allow Mixin remapping verbosity off.
            add("-Dmixin.env.remapRefMap=true")
            add("-Dfabric.log.disableAnsi=true")
            add("-Dorg.lwjgl.util.DebugLoader=false")
            add("-Dorg.lwjgl.glfw.checkThread0=false")
        }
    }

    /** OptiFine / LaunchWrapper: vanilla Android args + version jvm (no Forge JPMS). */
    private fun buildOptiFineJvmArgs(
        jarFile: File,
        gameDir: File,
        maxMemoryMb: Int,
        windowWidth: Int,
        windowHeight: Int,
        javaHome: File,
        javaMajor: Int,
        classpath: String,
        renderer: GlRendererKind,
        versionRoot: JSONObject,
        tokens: Map<String, String>,
        jnaBootPath: String,
        sdlWindowing: Boolean = false
    ): List<String> {
        val nativeDir = AndroidGameRuntime.nativesDir().absolutePath
        val versionJvm = parseVersionJvmArgs(versionRoot, tokens, nativeDir)
        return buildList {
            addAll(
                buildCommonAndroidJvmArgs(
                    jarFile = jarFile,
                    gameDir = gameDir,
                    maxMemoryMb = maxMemoryMb,
                    windowWidth = windowWidth,
                    windowHeight = windowHeight,
                    javaHome = javaHome,
                    javaMajor = javaMajor,
                    classpath = classpath,
                    renderer = renderer,
                    forgeExtras = false,
                    jnaBootPath = jnaBootPath,
                    sdlWindowing = sdlWindowing
                )
            )
            addAll(versionJvm)
        }
    }

    private fun isKnotMainClass(mainClass: String): Boolean {
        val lowered = mainClass.lowercase()
        return "knotclient" in lowered ||
            "fabricmc.loader" in lowered ||
            "net.fabricmc" in lowered ||
            "org.quiltmc.loader" in lowered ||
            "quiltmc.loader" in lowered
    }

    private fun isOptiFineVersion(
        versionId: String,
        mainClass: String,
        root: JSONObject
    ): Boolean {
        if ("optifine" in versionId.lowercase()) return true
        if ("launchwrapper" in mainClass.lowercase()) return true
        val game = root.optJSONObject("arguments")?.optJSONArray("game")
        if (game != null) {
            for (i in 0 until game.length()) {
                val item = game.opt(i)
                if (item is String && item.contains("optifine", ignoreCase = true)) return true
            }
        }
        val legacy = root.optString("minecraftArguments")
        return legacy.contains("OptiFine", ignoreCase = true)
    }

    /** Forge / mod-loader JVM args from version JSON + Java 9+ exports. */
    private fun buildForgeJvmArgs(
        jarFile: File,
        gameDir: File,
        maxMemoryMb: Int,
        windowWidth: Int,
        windowHeight: Int,
        javaHome: File,
        javaMajor: Int,
        classpath: String,
        renderer: GlRendererKind,
        mainClass: String,
        versionRoot: JSONObject,
        tokens: Map<String, String>,
        mcVersionId: String,
        jnaBootPath: String,
        legacyClasspathFile: File? = null,
        legacyClasspathEntryCount: Int = 0,
        sdlWindowing: Boolean = false
    ): List<String> {
        val nativeDir = AndroidGameRuntime.nativesDir().absolutePath
        val versionJvm = parseVersionJvmArgs(versionRoot, tokens, nativeDir)
        val ignoreExtras = listOfNotNull(
            jarFile.name,
            // 原版 jar 不要变成模块名 _1._20._1
            // next to forge client module "minecraft" (ResolutionException / flywheel).
            File(LauncherPaths.versionsDir, "$mcVersionId/$mcVersionId.jar")
                .takeIf { it.isFile }
                ?.name
        ).distinct()
        return buildList {
            addAll(
                buildCommonAndroidJvmArgs(
                    jarFile = jarFile,
                    gameDir = gameDir,
                    maxMemoryMb = maxMemoryMb,
                    windowWidth = windowWidth,
                    windowHeight = windowHeight,
                    javaHome = javaHome,
                    javaMajor = javaMajor,
                    classpath = classpath,
                    renderer = renderer,
                    forgeExtras = true,
                    jnaBootPath = jnaBootPath,
                    sdlWindowing = sdlWindowing
                )
            )
            if (legacyClasspathFile != null) {
                add("-DlegacyClassPath.file=${legacyClasspathFile.absolutePath}")
                android.util.Log.i(
                    "LaunchCmd",
                    "Forge BSL: short -cp (${classpath.split(File.pathSeparator).size}), " +
                        "legacyClassPath.file lines=$legacyClasspathEntryCount"
                )
            }
            addAll(patchIgnoreList(versionJvm, ignoreExtras))
            // cpw BootstrapLauncher (Forge 1.17–1.20.2) needs this on Java 9+.
            // Do NOT invent exports for net.minecraftforge.bootstrap.ForgeBootstrap —
            // that named module is loaded from -cp via SecureJar and bad --add-exports
            // break boot-layer resolution on some devices (Huawei).
            if (javaMajor != 8 && usesBootstrapLauncher(mainClass)) {
                add("--add-exports")
                add("cpw.mods.bootstraplauncher/cpw.mods.bootstraplauncher=ALL-UNNAMED")
            }
            // Extra ALL-UNNAMED opens: version JSON opens to cpw.mods.securejarhandler,
            // but that module is not visible to unnamed bootstrap code until -p is applied.
            if (javaMajor >= 9) {
                addAll(forgeUnnamedModuleOpens())
            }
            // Drop JVM flags that crash older Android JREs (e.g. Java 21 vs CompactObjectHeaders).
            if (javaMajor < 24) {
                removeAll { it.contains("UseCompactObjectHeaders") }
            }
        }
    }

    /** Append extra jars to -DignoreList so they aren't loaded as modules. */
    private fun patchIgnoreList(jvmArgs: List<String>, extraIgnores: List<String>): List<String> {
        if (extraIgnores.isEmpty()) return jvmArgs
        var found = false
        val out = jvmArgs.map { arg ->
            if (!arg.startsWith("-DignoreList=")) return@map arg
            found = true
            val current = arg.removePrefix("-DignoreList=").split(',').filter { it.isNotBlank() }
            "-DignoreList=" + (current + extraIgnores).distinct().joinToString(",")
        }
        return if (found) {
            out
        } else {
            out + ("-DignoreList=" + extraIgnores.joinToString(","))
        }
    }

    private fun forgeUnnamedModuleOpens(): List<String> = buildList {
        val targets = listOf(
            "java.base/java.lang.invoke",
            "java.base/java.lang",
            "java.base/java.lang.reflect",
            "java.base/java.io",
            "java.base/java.util",
            "java.base/java.util.concurrent",
            "java.base/java.net",
            "java.base/java.nio",
            "java.base/java.nio.file",
            "java.base/java.util.jar",
            "java.base/jdk.internal.loader",
            "java.base/sun.nio.ch",
            "java.base/sun.security.util"
        )
        for (target in targets) {
            add("--add-opens")
            add("$target=ALL-UNNAMED")
        }
    }

    private fun buildCommonAndroidJvmArgs(
        jarFile: File,
        gameDir: File,
        maxMemoryMb: Int,
        windowWidth: Int,
        windowHeight: Int,
        javaHome: File,
        javaMajor: Int,
        classpath: String,
        renderer: GlRendererKind,
        forgeExtras: Boolean,
        jnaBootPath: String,
        sdlWindowing: Boolean = false
    ): List<String> {
        val nativeDir = AndroidGameRuntime.nativesDir().absolutePath
        val glLibName = RuntimeEnv.glLibraryFile(
            AndroidGameRuntime.nativesDir(),
            renderer
        ).absolutePath
        return buildList {
            add("-Xmx${maxMemoryMb}m")
            add("-Xms64m")
            // Android OpenJDK: CDS mmap / hsperfdata /dev/random 都可能让 CreateJavaVM 长时间卡住
            add("-Xshare:off")
            add("-XX:-UsePerfData")
            add("-XX:+DisableAttachMechanism")
            add("-Djava.security.egd=file:/dev/./urandom")
            add("-Dsecurerandom.source=file:/dev/urandom")
            add("-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors()}")
            // Booxin launch tune: G1 + short pause goals (no mods).
            addAll(BooxinLaunchTune.jvmPerformanceArgs(maxMemoryMb))
            if (javaMajor >= 17) {
                add("--enable-native-access=ALL-UNNAMED")
            }
            add("-Djava.home=${javaHome.absolutePath}")
            // Prefer -cp argv for classpath; avoid a second multi-100KB -Djava.class.path= option.
            // (parse_args still rebuilds a single -Djava.class.path from -cp.)
            if (classpath.isNotBlank()) {
                android.util.Log.i(
                    "LaunchCmd",
                    "classpath for -cp ≈${classpath.count { it == File.pathSeparatorChar } + 1} entries, " +
                        "chars=${classpath.length}"
                )
            }
            add("-Djava.rmi.server.useCodebaseOnly=true")
            add("-Dcom.sun.jndi.rmi.object.trustURLCodebase=false")
            add("-Dlog4j2.formatMsgNoLookups=true")
            add("-Dminecraft.client.jar=${jarFile.absolutePath}")
            // Android uses a patched LWJGL (reports 3.3.6-snapshot). Sodium's
            // issue#2561 gate expects desktop Mojang LWJGL (e.g. 3.3.3) and aborts
            // Same bypass as other Android launchers.
            add("-Dsodium.checks.issue2561=false")
            if (forgeExtras) {
                add("-Dfml.ignoreInvalidMinecraftCertificates=true")
                add("-Dfml.ignorePatchDiscrepancies=true")
                // Must stay off on Android: DisplayWindow / earlydisplay crashes GLFW stub.
                add("-Dfml.earlyprogresswindow=false")
                add("-Dfml.earlyWindowSkip=true")
                add("-Dfml.earlyWindowControl=false")
                add("-Dloader.disable_forked_guis=true")
            }
            add("-Djava.io.tmpdir=${context.cacheDir.absolutePath}")
            add("-Dos.name=Linux")
            // Linux-like version: avoid advertising "Android-*" to mods that
            // fingerprint the JVM. POJAV_RENDERER is also withheld from Java getenv
            // (Create brands it as PojavLauncher); LWJGL reads BOOXIN_RENDERER.
            add("-Dos.version=5.10.0-booxin")
            add("-Duser.home=${gameDir.absolutePath}")
            add("-Duser.language=zh")
            add("-Duser.country=CN")
            add("-Duser.timezone=${TimeZone.getDefault().id}")
            add("-Djdk.lang.Process.launchMechanism=FORK")
            add("-Dglfwstub.windowWidth=$windowWidth")
            add("-Dglfwstub.windowHeight=$windowHeight")
            add("-Dglfwstub.initEgl=false")
            add("-Djava.library.path=$nativeDir")
            add("-Dorg.lwjgl.librarypath=$nativeDir")
            add("-Dorg.lwjgl.opengl.libname=$glLibName")
            // Android LWJGL GLFW$Functions resolves bridge symbols from the GLFW SharedLibrary.
            // Point it at our bridge so Forge/module-layer loads don't fall back to X11.
            // SDL (26.3+): skip — accidental GLFW SharedLibrary load would System.load
            // the bridge before org.lwjgl.system.Library on the correct ClassLoader.
            if (!sdlWindowing) {
                val glfwBridge = File(nativeDir, "libbooxin_bridge.so").takeIf { it.isFile }
                    ?: File(nativeDir, "libpojavexec.so")
                if (glfwBridge.isFile) {
                    add("-Dorg.lwjgl.glfw.libname=${glfwBridge.absolutePath}")
                }
            }
            add("-Dorg.lwjgl.freetype.libname=$nativeDir/libfreetype.so")
            add("-Dorg.lwjgl.openal.libname=$nativeDir/libopenal.so")
            // Do NOT set org.lwjgl.vulkan.libname on GLES path: MC 26.x then calls
            // VK.getVulkanDriverHandle() (we return 0) → NPE in SharedLibrary ctor.
            // Vulkan/Zink is a separate renderer; leave Vulkan probe to fail cleanly.
            if (renderer == GlRendererKind.VULKAN_ZINK) {
                add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
            }
            add("-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared")
            add("-Dorg.lwjgl.shaderc.libname=shaderc")
            val sdl3 = File(nativeDir, "libSDL3.so")
            if (sdl3.isFile) {
                add("-Dorg.lwjgl.sdl.libname=${sdl3.absolutePath}")
            }
            // Match Android libjnidispatch.so to the jna-*.jar on the classpath.
            // Mismatch → Native init fails → OSHI Quartet NoClassDefFoundError on Forge.
            add("-Djna.boot.library.path=$jnaBootPath:$nativeDir")
            add("-Djna.nosys=true")
            add("-Djna.nounpack=true")
            add("-Djna.tmpdir=${context.cacheDir.absolutePath}")
            // Android has no netty epoll/kqueue natives; force NIO.
            add("-Dio.netty.transport.noNative=true")
            add("-Dio.netty.noUnsafe=true")
        }
    }

    /**
     * Parse merged [arguments.jvm], skipping `-cp` / `${classpath}` (we pass -cp separately).
     * Remap natives/tmp extract paths into our cache dir.
     */
    private fun parseVersionJvmArgs(
        root: JSONObject,
        tokens: Map<String, String>,
        nativeDir: String
    ): List<String> {
        val jvm = root.optJSONObject("arguments")?.optJSONArray("jvm") ?: return emptyList()
        val raw = ArrayList<String>()
        appendModernArgs(raw, jvm, tokens)
        val out = ArrayList<String>(raw.size)
        var skipNext = false
        for (arg in raw) {
            if (skipNext) {
                skipNext = false
                continue
            }
            if (arg == "-cp" || arg == "-classpath") {
                skipNext = true
                continue
            }
            if (arg == "\${classpath}" || arg == tokens["classpath"]) continue
            var result = arg
            if (arg.contains("-Dio.netty.native.workdir") ||
                arg.contains("-Djna.tmpdir") ||
                arg.contains("-Dorg.lwjgl.system.SharedLibraryExtractPath")
            ) {
                result = arg.replace(nativeDir, context.cacheDir.absolutePath)
                    .replace("\${natives_directory}", context.cacheDir.absolutePath)
            }
            // Our asProcessCommand already sets -Djava.library.path; avoid duplicates from JSON.
            if (result.startsWith("-Djava.library.path=")) continue
            out += result
        }
        return out
    }

    private fun appendModernArgs(
        out: MutableList<String>,
        array: JSONArray,
        tokens: Map<String, String>
    ) {
        for (i in 0 until array.length()) {
            when (val item = array.get(i)) {
                is String -> out += substitute(item, tokens)
                is JSONObject -> {
                    if (!rulesAllow(item.optJSONArray("rules"))) continue
                    when (val value = item.opt("value")) {
                        is String -> out += substitute(value, tokens)
                        is JSONArray -> {
                            for (j in 0 until value.length()) {
                                out += substitute(value.getString(j), tokens)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun usesBootstrapLauncher(mainClass: String): Boolean =
        mainClass.contains("bootstraplauncher", ignoreCase = true)

    private fun disableForgeSplash(gameDir: File) {
        runCatching {
            val config = File(gameDir, "config")
            if (!config.isDirectory) config.mkdirs()
            File(config, "splash.properties").writeText("enabled=false")
            // NeoForge/FML 10+ ignores -Dfml.earlyWindowControl; it reads config/fml.toml.
            // Leaving earlyWindowControl=true loads earlydisplay → takeOverGlfwWindow NPE
            // on Android GLFW stubs (glfwSetWindowSizeCallback returns null).
            ensureEarlyWindowDisabled(File(config, "fml.toml"))
        }
    }

    /** Forge/NeoForge early loading screen JARs — unsafe on Android GLFW stubs. */
    private fun isEarlyDisplayLibrary(name: String): Boolean {
        val n = name.lowercase()
        if ("fmlearlydisplay" in n) return true
        val parts = n.substringBefore('@').split(':')
        val artifact = parts.getOrNull(1).orEmpty()
        return artifact == "earlydisplay"
    }

    /**
     * Force `earlyWindowControl=false` in FML's NightConfig file.
     * System properties alone do not disable NeoForge ImmediateWindowProvider.
     */
    private fun ensureEarlyWindowDisabled(fmlToml: File) {
        val existing = if (fmlToml.isFile) fmlToml.readText() else ""
        val key = Regex("""(?m)^\s*earlyWindowControl\s*=\s*\S+""")
        val updated = when {
            key.containsMatchIn(existing) ->
                key.replace(existing, "earlyWindowControl=false")
            existing.isBlank() ->
                "earlyWindowControl=false\n"
            else ->
                existing.trimEnd() + "\nearlyWindowControl=false\n"
        }
        if (updated != existing) {
            fmlToml.writeText(updated)
        }
    }

    private fun buildGameArgs(root: JSONObject, tokens: Map<String, String>): List<String> {
        val argsObj = root.optJSONObject("arguments")
        val modern = argsObj?.optJSONArray("game")
        if (modern != null) {
            val out = ArrayList<String>()
            for (i in 0 until modern.length()) {
                val item = modern.get(i)
                when (item) {
                    is String -> out += substitute(item, tokens)
                    is JSONObject -> {
                        if (rulesAllow(item.optJSONArray("rules"))) {
                            val value = item.opt("value")
                            when (value) {
                                is String -> out += substitute(value, tokens)
                                is JSONArray -> {
                                    for (j in 0 until value.length()) {
                                        out += substitute(value.getString(j), tokens)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return out.filter { it.isNotBlank() }
        }

        val legacy = root.optString("minecraftArguments")
        if (legacy.isNotBlank()) {
            return legacy.split(' ')
                .filter { it.isNotBlank() }
                .map { substitute(it, tokens) }
        }

        // Absolute fallback for very old formats.
        return listOf(
            "--username", tokens.getValue("auth_player_name"),
            "--version", tokens.getValue("version_name"),
            "--gameDir", tokens.getValue("game_directory"),
            "--assetsDir", tokens.getValue("assets_root"),
            "--assetIndex", tokens.getValue("assets_index_name"),
            "--uuid", tokens.getValue("auth_uuid"),
            "--accessToken", tokens.getValue("auth_access_token"),
            "--userType", tokens.getValue("user_type"),
            "--versionType", tokens.getValue("version_type")
        )
    }

    private fun forceVersionType(
        args: List<String>,
        brand: String,
        root: JSONObject
    ): List<String> {
        val out = args.toMutableList()
        val idx = out.indexOf("--versionType")
        if (idx >= 0 && idx + 1 < out.size) {
            out[idx + 1] = brand
            return out
        }
        // Modern clients (arguments.game) accept --versionType; legacy minecraftArguments
        // + LaunchWrapper 1.5 do not — leave args untouched.
        val hasModernGameArgs = root.optJSONObject("arguments")?.optJSONArray("game") != null
        if (hasModernGameArgs) {
            out += listOf("--versionType", brand)
        }
        return out
    }

    /**
     * Auto-join EasyTier local forward / official server after lobby join.
     * 1.20+ prefers --quickPlayMultiplayer; older clients use --server/--port.
     * 覆盖已有 server/quickPlay 参数，避免空白
     * placeholder from version.json cannot win.
     */
    private fun appendServerArgs(
        args: List<String>,
        serverAddress: String?,
        mcVersionId: String
    ): List<String> {
        val raw = serverAddress?.trim().orEmpty()
        if (raw.isEmpty()) return args

        val host = raw.substringBefore(':')
            .trim()
            .filterNot { it.isWhitespace() }
            .ifBlank { "127.0.0.1" }
        val portPart = raw.substringAfter(':', missingDelimiterValue = "25565").trim()
        val port = portPart.toIntOrNull() ?: 25565
        // Mod loaders (Forge/Fabric/…) often mishandle quickPlay feature-gated args
        // on Android → connect fails with "Invalid argument". Prefer classic --server.
        val useQuickPlay = supportsQuickPlay(mcVersionId) && !isModdedLoader(mcVersionId)

        val cleaned = stripServerArgs(args)
        return cleaned + if (useQuickPlay) {
            listOf("--quickPlayMultiplayer", "$host:$port")
        } else {
            listOf("--server", host, "--port", port.toString())
        }
    }

    private fun stripServerArgs(args: List<String>): List<String> {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--server" || a == "--port" || a == "--quickPlayMultiplayer" -> {
                    i += 2 // skip flag + value
                }
                a.startsWith("--server=") ||
                    a.startsWith("--port=") ||
                    a.startsWith("--quickPlayMultiplayer=") -> {
                    i += 1
                }
                else -> {
                    out += a
                    i += 1
                }
            }
        }
        return out
    }

    private fun supportsQuickPlay(mcVersionId: String): Boolean {
        val ver = MinecraftJavaRequirement.parseVersion(mcVersionId)
        if (ver == null) {
            val major = mcVersionId.substringBefore('.').toIntOrNull() ?: return false
            return major >= 20
        }
        return ver.first > 1 || (ver.first == 1 && ver.second >= 20)
    }

    private fun isModdedLoader(mcVersionId: String): Boolean {
        val id = mcVersionId.lowercase(Locale.US)
        return "forge" in id || "fabric" in id || "quilt" in id ||
            "neoforge" in id || "optifine" in id || "liteloader" in id
    }

    private fun substitute(raw: String, tokens: Map<String, String>): String {
        var result = raw
        for ((key, value) in tokens) {
            result = result.replace("\${$key}", value)
        }
        return result
    }

    private fun rulesAllow(rules: JSONArray?): Boolean {
        if (rules == null || rules.length() == 0) return true
        var allowed = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val action = rule.optString("action", "allow")
            val os = rule.optJSONObject("os")
            val feature = rule.optJSONObject("features")
            // Skip feature-gated args (demo, custom resolution) unless we set features.
            if (feature != null) continue
            // 按 Linux 规则过滤 libraries。
            val matches = when {
                os == null -> true
                else -> {
                    val name = os.optString("name").lowercase(Locale.US)
                    name.isBlank() || name == "linux" || name == "unix"
                }
            }
            if (matches) allowed = action == "allow"
        }
        return allowed
    }

    private fun buildLibraryPath(
        javaHome: File,
        stagedNatives: String,
        pluginNatives: String? = null
    ): String {
        val parts = mutableListOf<String>()
        // staged natives 优先于 APK 内 holy-gl4es。
        parts += stagedNatives
        if (!pluginNatives.isNullOrBlank()) parts += pluginNatives
        listOf(
            File(javaHome, "lib"),
            File(javaHome, "lib/aarch64"),
            File(javaHome, "lib/arm"),
            File(javaHome, "lib/server"),
            File(javaHome, "lib/client"),
            File(javaHome, "jre/lib"),
            File(javaHome, "jre/lib/aarch64"),
            File(javaHome, "jre/lib/arm")
        ).forEach { if (it.exists()) parts += it.absolutePath }
        // APK nativeLibraryDir 放最后，避免系统/APK 的 gl4es 抢先。
        parts += context.applicationInfo.nativeLibraryDir
        parts += "/system/lib64"
        parts += "/system/lib"
        parts += "/vendor/lib64"
        parts += "/vendor/lib"
        return parts.joinToString(":")
    }

}


