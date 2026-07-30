package com.booxin.launcher.core.launch

import android.content.Context
import android.os.Build
import com.booxin.launcher.BuildConfig
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.GameJsonParser
import com.booxin.launcher.core.download.game.LibraryFilter
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.java.InstalledJavaRuntime
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
            appendLine("game=${gameArgs.joinToString(" ")}")
        }
    }
}

/**
 * Builds a Minecraft client command line using FCL/HMCL-style Android adaptations:
 * -Dos.name=Linux, glfwstub window size, filtered libraries, offline auth tokens.
 */
class LaunchCommandBuilder(
    private val context: Context
) {

    fun build(
        versionId: String,
        username: String,
        java: InstalledJavaRuntime,
        maxMemoryMb: Int = 1024,
        windowWidth: Int = 854,
        windowHeight: Int = 480,
        uuid: String? = null,
        accessToken: String? = null,
        userType: String? = null
    ): LaunchCommand {
        val versionRoot = File(LauncherPaths.versionsDir, versionId)
        val jsonFile = File(versionRoot, "$versionId.json")
        require(jsonFile.exists()) { "缺少 version.json: ${jsonFile.absolutePath}" }

        val root = VersionJsonMerger.merge(versionId)
            ?: error("无法合并 version.json: $versionId")
        val jarFile = VersionJsonMerger.resolveClientJar(versionId)
            ?: error("缺少客户端 jar: $versionId")
        require(jarFile.isFile) { "缺少客户端 jar: ${jarFile.absolutePath}" }

        val mainClass = root.optString("mainClass").ifBlank {
            error("version.json 缺少 mainClass")
        }
        val assetIndexId = root.optJSONObject("assetIndex")?.optString("id")
            ?: root.optString("assets").ifBlank { "legacy" }

        AndroidGameRuntime.ensure(context)
        val renderer = GlRendererProfile.forVersion(versionId)
        AndroidGameRuntime.applyRenderer(renderer)
        val bridgePatch = AndroidGameRuntime.lwjglBridgePatchJar()
        val androidLwjgl = AndroidGameRuntime.lwjglJar()
        require(bridgePatch.isFile) {
            "缺少 LWJGL bridge patch: ${bridgePatch.absolutePath}"
        }
        require(androidLwjgl.isFile) {
            "缺少 Android LWJGL: ${androidLwjgl.absolutePath}"
        }

        val classpath = linkedSetOf<File>()
        // Use the bridge patch jar ahead of lwjgl.jar so embedded HotSpot resolves
        // the Android-compatible CallbackBridge instead of the desktop stub.
        classpath += bridgePatch
        classpath += androidLwjgl
        val libraries = root.optJSONArray("libraries") ?: JSONArray()
        for (i in 0 until libraries.length()) {
            val lib = libraries.getJSONObject(i)
            val name = lib.getString("name")
            if (!LibraryFilter.shouldKeep(name)) continue
            // Keep artifact jars even if the library also declares desktop natives.
            val artifact = lib.optJSONObject("downloads")?.optJSONObject("artifact")
            if (artifact == null && lib.has("natives")) continue
            if (!rulesAllow(lib.optJSONArray("rules"))) continue
            val path = artifact?.optString("path")?.ifBlank { null }
                ?: GameJsonParser.mavenPath(name)
            val file = File(LauncherPaths.librariesDir, path)
            if (file.exists()) classpath += file
        }
        classpath += jarFile
        val existingClasspath = classpath.filter { it.exists() }
        require(existingClasspath.isNotEmpty()) { "classpath 为空，缺少可用 jar" }
        require(jarFile.exists()) { "缺少客户端 jar: ${jarFile.absolutePath}" }

        val gameDir = versionRoot
        val assetsDir = LauncherPaths.assetsDir
        val resolvedUuid = uuid?.replace("-", "")?.ifBlank { null }
            ?: OfflineAuth.uuidNoDash(username)
        val resolvedToken = accessToken?.ifBlank { null } ?: "0"
        val resolvedUserType = userType?.ifBlank { null }
            ?: if (resolvedToken != "0") "msa" else "legacy"
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
            "version_type" to root.optString("type", "release"),
            "user_properties" to userProperties,
            "auth_session" to resolvedToken,
            "game_assets" to File(assetsDir, "virtual/$assetIndexId").absolutePath,
            "natives_directory" to AndroidGameRuntime.nativesDir().absolutePath,
            "launcher_name" to "BooxinLauncher",
            "launcher_version" to BuildConfig.VERSION_NAME,
            "classpath" to existingClasspath.joinToString(File.pathSeparator) { it.absolutePath },
            "resolution_width" to windowWidth.toString(),
            "resolution_height" to windowHeight.toString(),
            "library_directory" to LauncherPaths.librariesDir.absolutePath,
            "classpath_separator" to File.pathSeparator,
            "primary_jar" to jarFile.absolutePath,
            "language" to Locale.getDefault().toString()
        )

        val classpathString = existingClasspath.joinToString(File.pathSeparator) { it.absolutePath }
        val jvmArgs = buildJvmArgs(
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

        val gameArgs = buildGameArgs(root, tokens)

        val stagedNatives = AndroidGameRuntime.nativesDir().absolutePath
        val glLib = when (renderer) {
            GlRendererKind.GL4ES -> File(stagedNatives, "libgl4es_114.so")
            GlRendererKind.MOBILE_GLUES -> File(stagedNatives, "libmobileglues.so")
        }
        require(glLib.isFile) { "缺少渲染库: ${glLib.absolutePath}" }
        val libraryPath = buildLibraryPath(java.homeDir, stagedNatives)
        // Zalith/FCL: POJAV_RENDERER must stay opengles* or br_init stays NULL.
        val env = when (renderer) {
            GlRendererKind.GL4ES -> linkedMapOf(
                "JAVA_HOME" to java.homeDir.absolutePath,
                "HOME" to gameDir.absolutePath,
                "TMPDIR" to context.cacheDir.absolutePath,
                "PATH" to "${File(java.homeDir, "bin").absolutePath}:${System.getenv("PATH").orEmpty()}",
                "LD_LIBRARY_PATH" to libraryPath,
                "POJAV_NATIVEDIR" to stagedNatives,
                "FCL_NATIVEDIR" to stagedNatives,
                "POJAV_RENDERER" to "opengles2",
                "LIBGL_ES" to "2",
                "LIBGL_NAME" to glLib.absolutePath,
                "LIBGL_STRING" to "GL4ES",
                "LIBGL_EGL" to "libEGL.so",
                "POJAVEXEC_EGL" to "libEGL.so",
                "LIBGL_NOERROR" to "1",
                "LIBGL_MIPMAP" to "3",
                "LIBGL_NOINTOVLHACK" to "1",
                "LIBGL_NORMALIZE" to "1",
                "FORCE_VSYNC" to "false",
                "AWTSTUB_WIDTH" to windowWidth.toString(),
                "AWTSTUB_HEIGHT" to windowHeight.toString(),
                "MESA_GLSL_CACHE_DIR" to context.cacheDir.absolutePath
            )
            GlRendererKind.MOBILE_GLUES -> linkedMapOf(
                "JAVA_HOME" to java.homeDir.absolutePath,
                "HOME" to gameDir.absolutePath,
                "TMPDIR" to context.cacheDir.absolutePath,
                "PATH" to "${File(java.homeDir, "bin").absolutePath}:${System.getenv("PATH").orEmpty()}",
                "LD_LIBRARY_PATH" to libraryPath,
                "POJAV_NATIVEDIR" to stagedNatives,
                "FCL_NATIVEDIR" to stagedNatives,
                "POJAV_RENDERER" to "opengles3",
                "LIBGL_ES" to "3",
                "LIBGL_NAME" to glLib.absolutePath,
                "LIBGL_STRING" to "MobileGlues",
                "LIBGL_EGL" to "libmobileglues.so",
                "POJAVEXEC_EGL" to "libmobileglues.so",
                "LIBGL_NOERROR" to "1",
                "LIBGL_MIPMAP" to "3",
                "LIBGL_NOINTOVLHACK" to "1",
                "LIBGL_NORMALIZE" to "1",
                "FORCE_VSYNC" to "false",
                "AWTSTUB_WIDTH" to windowWidth.toString(),
                "AWTSTUB_HEIGHT" to windowHeight.toString(),
                "MESA_GLSL_CACHE_DIR" to context.cacheDir.absolutePath,
                "MG_DIR_PATH" to File(context.filesDir, "MG").absolutePath,
                "allow_higher_compat_version" to "true",
                "allow_glsl_extension_directive_midshader" to "true",
                "force_glsl_extensions_warn" to "true"
            )
        }

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

    private fun buildJvmArgs(
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
        val nativeDir = AndroidGameRuntime.nativesDir().absolutePath
        val jnaPath = buildJnaBootLibraryPath()
        val glLibName = when (renderer) {
            GlRendererKind.GL4ES -> "$nativeDir/libgl4es_114.so"
            GlRendererKind.MOBILE_GLUES -> "$nativeDir/libmobileglues.so"
        }
        return buildList {
            add("-Xmx${maxMemoryMb}m")
            add("-Xms64m")
            add("-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors()}")
            // JDK 17+ only — Java 8 (MC ≤1.16.5) rejects unrecognized options.
            if (javaMajor >= 17) {
                add("--enable-native-access=ALL-UNNAMED")
            }
            add("-Djava.home=${javaHome.absolutePath}")
            add("-Djava.class.path=$classpath")
            add("-Djava.rmi.server.useCodebaseOnly=true")
            add("-Dcom.sun.jndi.rmi.object.trustURLCodebase=false")
            add("-Dlog4j2.formatMsgNoLookups=true")
            add("-Dminecraft.client.jar=${jarFile.absolutePath}")
            add("-Dfml.ignoreInvalidMinecraftCertificates=true")
            add("-Dfml.ignorePatchDiscrepancies=true")
            add("-Djava.io.tmpdir=${context.cacheDir.absolutePath}")
            add("-Dos.name=Linux")
            add("-Dos.version=Android-${Build.VERSION.RELEASE}")
            add("-Duser.home=${gameDir.absolutePath}")
            add("-Duser.language=${Locale.getDefault().language}")
            add("-Duser.country=${Locale.getDefault().country}")
            add("-Duser.timezone=${TimeZone.getDefault().id}")
            add("-Dfml.earlyprogresswindow=false")
            add("-Dloader.disable_forked_guis=true")
            add("-Djdk.lang.Process.launchMechanism=FORK")
            add("-Dglfwstub.windowWidth=$windowWidth")
            add("-Dglfwstub.windowHeight=$windowHeight")
            add("-Dglfwstub.initEgl=false")
            // Staged filesDir natives — system nativeLibraryDir is empty when not extracted.
            add("-Djava.library.path=$nativeDir")
            add("-Dorg.lwjgl.librarypath=$nativeDir")
            add("-Dorg.lwjgl.opengl.libname=$glLibName")
            add("-Dorg.lwjgl.freetype.libname=$nativeDir/libfreetype.so")
            add("-Dorg.lwjgl.openal.libname=$nativeDir/libopenal.so")
            add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
            // mapLibraryNameBundled adds lib…/.so — pass base names only.
            add("-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared")
            add("-Dorg.lwjgl.shaderc.libname=shaderc")
            add("-Djna.boot.library.path=$jnaPath:$nativeDir")
            add("-Djna.nosys=true")
            add("-Djna.nounpack=true")
            add("-Djna.tmpdir=${context.cacheDir.absolutePath}")
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
            val matches = os == null // Android: only rules without OS restriction
            if (matches) allowed = action == "allow"
        }
        return allowed
    }

    private fun buildLibraryPath(javaHome: File, stagedNatives: String): String {
        val parts = mutableListOf<String>()
        // Staged natives first so libmobileglues / disguised libgl4es win over APK holy-gl4es.
        parts += stagedNatives
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
