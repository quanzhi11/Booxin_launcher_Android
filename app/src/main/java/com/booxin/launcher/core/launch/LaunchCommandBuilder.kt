package com.booxin.launcher.core.launch

import android.content.Context
import android.os.Build
import com.booxin.launcher.BuildConfig
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.GameJsonParser
import com.booxin.launcher.core.download.game.LibraryFilter
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.java.InstalledJavaRuntime
import com.booxin.launcher.core.java.MinecraftJavaRequirement
import com.booxin.launcher.core.runtime.RendererBackend
import com.booxin.launcher.core.runtime.RuntimeEnv
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.TimeZone

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
            appendLine("jvm=${jvmArgs.size} args")
            val clientJar = jvmArgs.firstOrNull { it.startsWith("-Dminecraft.client.jar=") }
                ?: jvmArgs.firstOrNull { it.startsWith("-Dfabric.gameJarPath=") }
            appendLine("clientJar=${clientJar ?: "MISSING"}")
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
            // Fabric/Quilt normally have no JPMS module-path — MISSING is OK for Knot.
            appendLine("modulePath=${modulePath?.let { "yes (${it.split(File.pathSeparator).size} entries)" } ?: "n/a"}")
            appendLine("ignoreList=${jvmArgs.firstOrNull { it.startsWith("-DignoreList=") } ?: "default"}")
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
        serverAddress: String? = null
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
        val requestedRenderer = RendererBackend.kindForVersion(mcVersionId)
        val renderer = resolveRendererOrFallback(requestedRenderer, mcVersionId)
        AndroidGameRuntime.applyRenderer(renderer)
        val androidLwjgl = AndroidGameRuntime.lwjglJar()
        require(androidLwjgl.isFile) {
            "缺少 Android LWJGL: ${androidLwjgl.absolutePath}"
        }

        val isForgeOrLoader = VersionJsonMerger.isModLoaderVersion(versionId)

        val classpath = linkedSetOf<File>()
        val missingLibs = mutableListOf<String>()
        // One lwjgl.jar only — a second org.lwjgl module breaks ForgeBootstrap.
        classpath += androidLwjgl
        val libraries = root.optJSONArray("libraries") ?: JSONArray()
        for (i in 0 until libraries.length()) {
            val lib = libraries.getJSONObject(i)
            val name = lib.getString("name")
            if (!LibraryFilter.shouldKeep(name)) continue
            // earlydisplay opens a second GLFW window and races our Surface.
            if (isForgeOrLoader && name.contains("fmlearlydisplay", ignoreCase = true)) continue
            // Keep artifact jars even if the library also declares desktop natives.
            val artifact = lib.optJSONObject("downloads")?.optJSONObject("artifact")
            if (artifact == null && lib.has("natives")) continue
            if (!rulesAllow(lib.optJSONArray("rules"))) continue
            val path = artifact?.optString("path")?.ifBlank { null }
                ?: GameJsonParser.mavenPath(name)
            val file = File(LauncherPaths.librariesDir, path)
            if (file.isFile && file.length() > 0L) {
                classpath += file
            } else {
                missingLibs += name
            }
        }
        classpath += jarFile
        val existingClasspath = classpath.filter { it.isFile && it.length() > 0L }
        require(existingClasspath.isNotEmpty()) { "classpath 为空，缺少可用 jar" }
        require(jarFile.isFile && jarFile.length() > 0L) {
            "缺少客户端 jar（Fabric/Forge 需继承原版 jar）: ${jarFile.absolutePath}"
        }
        // Fabric Knot fails with "couldn't locate the game" when deps are incomplete.
        if (missingLibs.isNotEmpty()) {
            val critical = missingLibs.filter {
                it.contains("fabric-loader", ignoreCase = true) ||
                    it.contains("intermediary", ignoreCase = true) ||
                    it.contains("sponge-mixin", ignoreCase = true) ||
                    it.contains(":mixin:", ignoreCase = true)
            }
            require(critical.isEmpty()) {
                "缺少关键依赖库，请重新安装该版本: ${critical.take(8).joinToString()}"
            }
        }

        val gameDir = versionRoot
        val assetsDir = LauncherPaths.assetsDir
        val resolvedUuid = uuid?.replace("-", "")?.ifBlank { null }
            ?: OfflineAuth.uuidNoDash(username)
        // Offline / empty token must stay legacy — never launch offline as msa (invalid session).
        val resolvedToken = accessToken?.ifBlank { null } ?: "0"
        val resolvedUserType = when {
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
            "auth_session" to resolvedToken,
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
            "language" to Locale.getDefault().toString()
        )

        val classpathString = existingClasspath.joinToString(File.pathSeparator) { it.absolutePath }
        val isKnotLoader = isKnotMainClass(mainClass)
        val isOptiFine = isOptiFineVersion(versionId, mainClass, root)
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
                classpath = classpathString,
                renderer = renderer,
                versionRoot = root,
                tokens = tokens,
                androidLwjgl = androidLwjgl,
                mainClass = mainClass
            )
            isOptiFine -> buildOptiFineJvmArgs(
                jarFile = jarFile,
                gameDir = gameDir,
                maxMemoryMb = maxMemoryMb,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                javaHome = java.homeDir,
                javaMajor = java.majorVersion,
                classpath = classpathString,
                renderer = renderer,
                versionRoot = root,
                tokens = tokens
            )
            isForgeOrLoader -> buildForgeJvmArgs(
                jarFile = jarFile,
                gameDir = gameDir,
                maxMemoryMb = maxMemoryMb,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                javaHome = java.homeDir,
                javaMajor = java.majorVersion,
                classpath = classpathString,
                renderer = renderer,
                mainClass = mainClass,
                versionRoot = root,
                tokens = tokens,
                mcVersionId = mcVersionId
            )
            else -> buildVanillaJvmArgs(
                jarFile = jarFile,
                gameDir = gameDir,
                maxMemoryMb = maxMemoryMb,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                javaHome = java.homeDir,
                javaMajor = java.majorVersion,
                classpath = classpathString,
                renderer = renderer
            )
        }

        val gameArgs = forceVersionType(buildGameArgs(root, tokens), LAUNCHER_BRAND)
            .let { appendServerArgs(it, serverAddress, mcVersionId) }

        val stagedNativesDir = AndroidGameRuntime.nativesDir()
        val stagedNatives = stagedNativesDir.absolutePath
        val glLib = RuntimeEnv.glLibraryFile(stagedNativesDir, renderer)
        require(glLib.isFile) { "缺少渲染库: ${glLib.absolutePath}" }
        val pluginDir = com.booxin.launcher.core.runtime.RendererInstaller.pluginNativeDir(renderer)
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
            "LIBGL_ES" to RuntimeEnv.libGlEs(renderer),
            "LIBGL_NAME" to glLib.absolutePath,
            "LIBGL_STRING" to RuntimeEnv.libGlString(renderer),
            "LIBGL_EGL" to RuntimeEnv.eglLib(renderer),
            "LIBGL_NOERROR" to "1",
            "LIBGL_MIPMAP" to "3",
            "LIBGL_NOINTOVLHACK" to "1",
            "LIBGL_NORMALIZE" to "1",
            "FORCE_VSYNC" to "false",
            "AWTSTUB_WIDTH" to windowWidth.toString(),
            "AWTSTUB_HEIGHT" to windowHeight.toString(),
            "MESA_GLSL_CACHE_DIR" to context.cacheDir.absolutePath
        )
        if (renderer == GlRendererKind.MOBILE_GLUES) {
            envBase["MG_DIR_PATH"] = File(context.filesDir, "MG").absolutePath
            envBase["allow_higher_compat_version"] = "true"
            envBase["allow_glsl_extension_directive_midshader"] = "true"
            envBase["force_glsl_extensions_warn"] = "true"
        }
        RuntimeEnv.pluginExtraEnv(renderer).forEach { (k, v) -> envBase[k] = v }
        val env = RuntimeEnv.withNativeAliases(envBase, stagedNatives, renderer)

        return LaunchCommand(
            javaBinary = java.javaBinary,
            javaHome = java.homeDir,
            workingDir = gameDir,
            jvmArgs = jvmArgs,
            mainClass = mainClass,
            gameArgs = gameArgs,
            classpath = existingClasspath,
            env = env
        )
    }

    /** Vanilla JVM args (no Forge module-path). */
    private fun resolveRendererOrFallback(
        requested: GlRendererKind,
        mcVersionId: String
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
        val fallback = GlRendererProfile.forVersion(mcVersionId).let { auto ->
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && auto == GlRendererKind.GL4ES) {
                GlRendererKind.MOBILE_GLUES
            } else {
                auto
            }
        }
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
        renderer: GlRendererKind
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
            forgeExtras = false
        )
    }

    /**
     * Fabric/Quilt Knot: keep Android LWJGL on the app (system) classloader.
     * HotSpot preinit already System.loads the bridge/LWJGL via AppClassLoader;
     * if Knot reloads org.lwjgl.* it hits "already loaded in another classloader".
     *
     * Fabric uses `fabric.*` props; Quilt uses `loader.*` — set both for safety.
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
        mainClass: String
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
                    forgeExtras = false
                )
            )
            addAll(versionJvm)
            // Point Knot at the real vanilla client jar (often parent of fabric-* id).
            add("-Dfabric.gameJarPath=${jarFile.absolutePath}")
            add("-Dloader.gameJarPath=${jarFile.absolutePath}")
            // Fabric props
            add("-Dfabric.systemLibraries=${androidLwjgl.absolutePath}")
            add("-Dfabric.noGui=true")
            // Quilt props (Fabric ignores unknown loader.* keys)
            add("-Dloader.systemLibraries=${androidLwjgl.absolutePath}")
            add("-Dloader.noGui=true")
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
        tokens: Map<String, String>
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
                    forgeExtras = false
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
        mcVersionId: String
    ): List<String> {
        val nativeDir = AndroidGameRuntime.nativesDir().absolutePath
        val versionJvm = parseVersionJvmArgs(versionRoot, tokens, nativeDir)
        val ignoreExtras = listOfNotNull(
            jarFile.name,
            // Parent vanilla jar e.g. 1.20.1.jar — must not become module "_1._20._1"
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
                    forgeExtras = true
                )
            )
            addAll(patchIgnoreList(versionJvm, ignoreExtras))
            // ForgeBootstrap: enable bootstrap debug to logcat via stdout capture.
            add("-Dbsl.debug=true")
            // BootstrapLauncher needs this on Java 9+.
            if (javaMajor != 8 && usesBootstrapLauncher(mainClass)) {
                add("--add-exports")
                add("cpw.mods.bootstraplauncher/cpw.mods.bootstraplauncher=ALL-UNNAMED")
            }
            // Extra ALL-UNNAMED opens: version JSON opens to cpw.mods.securejarhandler,
            // but that module is not visible to unnamed bootstrap code until -p is applied.
            if (javaMajor >= 9) {
                addAll(forgeUnnamedModuleOpens())
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
        forgeExtras: Boolean
    ): List<String> {
        val nativeDir = AndroidGameRuntime.nativesDir().absolutePath
        val jnaPath = buildJnaBootLibraryPath()
        val glLibName = RuntimeEnv.glLibraryFile(
            AndroidGameRuntime.nativesDir(),
            renderer
        ).absolutePath
        return buildList {
            add("-Xmx${maxMemoryMb}m")
            add("-Xms64m")
            add("-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors()}")
            if (javaMajor >= 17) {
                add("--enable-native-access=ALL-UNNAMED")
            }
            add("-Djava.home=${javaHome.absolutePath}")
            add("-Djava.class.path=$classpath")
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
                // Disable Forge early loading window (DisplayWindow pulls GLFW early).
                add("-Dfml.earlyprogresswindow=false")
                add("-Dfml.earlyWindowSkip=true")
                add("-Dfml.earlyWindowControl=false")
                add("-Dloader.disable_forked_guis=true")
            }
            add("-Djava.io.tmpdir=${context.cacheDir.absolutePath}")
            add("-Dos.name=Linux")
            add("-Dos.version=Android-${Build.VERSION.RELEASE}")
            add("-Duser.home=${gameDir.absolutePath}")
            add("-Duser.language=${Locale.getDefault().language}")
            add("-Duser.country=${Locale.getDefault().country}")
            add("-Duser.timezone=${TimeZone.getDefault().id}")
            add("-Djdk.lang.Process.launchMechanism=FORK")
            add("-Dglfwstub.windowWidth=$windowWidth")
            add("-Dglfwstub.windowHeight=$windowHeight")
            add("-Dglfwstub.initEgl=false")
            add("-Djava.library.path=$nativeDir")
            add("-Dorg.lwjgl.librarypath=$nativeDir")
            add("-Dorg.lwjgl.opengl.libname=$glLibName")
            add("-Dorg.lwjgl.freetype.libname=$nativeDir/libfreetype.so")
            add("-Dorg.lwjgl.openal.libname=$nativeDir/libopenal.so")
            add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
            add("-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared")
            add("-Dorg.lwjgl.shaderc.libname=shaderc")
            add("-Djna.boot.library.path=$jnaPath:$nativeDir")
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

    private fun forceVersionType(args: List<String>, brand: String): List<String> {
        val out = args.toMutableList()
        val idx = out.indexOf("--versionType")
        if (idx >= 0 && idx + 1 < out.size) {
            out[idx + 1] = brand
        } else {
            out += listOf("--versionType", brand)
        }
        return out
    }

    /**
     * Auto-join EasyTier local forward / official server after lobby join.
     * 1.20+ prefers --quickPlayMultiplayer; older clients use --server/--port.
     * Always replaces any existing server/quickPlay args so a blank/spaced
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
        val useQuickPlay = supportsQuickPlay(mcVersionId)

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
            // We report as Linux — accept unrestricted / linux / unix rules.
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
        // Staged natives first so libmobileglues / disguised libgl4es win over APK holy-gl4es.
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
        // APK nativeLibraryDir last (real libgl4es_114.so must not win dlopen).
        parts += context.applicationInfo.nativeLibraryDir
        parts += "/system/lib64"
        parts += "/system/lib"
        parts += "/vendor/lib64"
        parts += "/vendor/lib"
        return parts.joinToString(":")
    }

    private fun buildJnaBootLibraryPath(): String {
        val versionRoot = File(LauncherPaths.runtimeDir, "jna/jna")
        val preferredVersions = listOf("5.15.0", "5.16.0", "5.14.0", "5.13.0")
        for (version in preferredVersions) {
            val dir = File(versionRoot, version)
            if (File(dir, "libjnidispatch.so").isFile) {
                return dir.absolutePath
            }
        }
        return AndroidGameRuntime.nativesDir().absolutePath
    }
}


