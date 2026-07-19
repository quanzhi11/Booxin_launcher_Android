package com.booxin.launcher.core.launch

import android.content.Context
import android.os.Build
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.GameJsonParser
import com.booxin.launcher.core.download.game.LibraryFilter
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
        maxMemoryMb: Int = 2048,
        windowWidth: Int = 854,
        windowHeight: Int = 480
    ): LaunchCommand {
        val versionRoot = File(LauncherPaths.versionsDir, versionId)
        val jsonFile = File(versionRoot, "$versionId.json")
        val jarFile = File(versionRoot, "$versionId.jar")
        require(jsonFile.exists()) { "缺少 version.json: ${jsonFile.absolutePath}" }
        require(jarFile.exists()) { "缺少客户端 jar: ${jarFile.absolutePath}" }

        val root = JSONObject(jsonFile.readText())
        val mainClass = root.optString("mainClass").ifBlank {
            error("version.json 缺少 mainClass")
        }
        val assetIndexId = root.optJSONObject("assetIndex")?.optString("id")
            ?: root.optString("assets").ifBlank { "legacy" }

        val classpath = linkedSetOf<File>()
        val libraries = root.optJSONArray("libraries") ?: JSONArray()
        for (i in 0 until libraries.length()) {
            val lib = libraries.getJSONObject(i)
            val name = lib.getString("name")
            if (!LibraryFilter.shouldKeep(name)) continue
            if (lib.has("natives")) continue
            if (!rulesAllow(lib.optJSONArray("rules"))) continue
            val artifact = lib.optJSONObject("downloads")?.optJSONObject("artifact")
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
        val nativesDir = File(LauncherPaths.rootDir, "natives/$versionId").also { it.mkdirs() }
        val assetsDir = LauncherPaths.assetsDir
        val uuidNoDash = OfflineAuth.uuidNoDash(username)
        val accessToken = "0"
        val userType = "legacy"
        val userProperties = "{}"

        val tokens = mapOf(
            "auth_player_name" to username,
            "version_name" to versionId,
            "game_directory" to gameDir.absolutePath,
            "assets_root" to assetsDir.absolutePath,
            "assets_index_name" to assetIndexId,
            "auth_uuid" to uuidNoDash,
            "auth_access_token" to accessToken,
            "user_type" to userType,
            "version_type" to root.optString("type", "release"),
            "user_properties" to userProperties,
            "auth_session" to accessToken,
            "game_assets" to File(assetsDir, "virtual/$assetIndexId").absolutePath,
            "natives_directory" to nativesDir.absolutePath,
            "launcher_name" to "BooxinLauncher",
            "launcher_version" to "0.1.0",
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
            nativesDir = nativesDir,
            gameDir = gameDir,
            maxMemoryMb = maxMemoryMb,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            javaHome = java.homeDir,
            classpath = classpathString
        )

        val gameArgs = buildGameArgs(root, tokens)

        val libraryPath = buildLibraryPath(java.homeDir)
        val env = linkedMapOf(
            "JAVA_HOME" to java.homeDir.absolutePath,
            "HOME" to gameDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "PATH" to "${File(java.homeDir, "bin").absolutePath}:${System.getenv("PATH").orEmpty()}",
            "LD_LIBRARY_PATH" to libraryPath,
            "POJAV_NATIVEDIR" to context.applicationInfo.nativeLibraryDir,
            "FCL_NATIVEDIR" to context.applicationInfo.nativeLibraryDir,
            "FORCE_VSYNC" to "false"
        )

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
        nativesDir: File,
        gameDir: File,
        maxMemoryMb: Int,
        windowWidth: Int,
        windowHeight: Int,
        javaHome: File,
        classpath: String
    ): List<String> {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return buildList {
            add("-Xmx${maxMemoryMb}m")
            add("-Xms512m")
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
            add("-Dglfwstub.windowWidth=$windowWidth")
            add("-Dglfwstub.windowHeight=$windowHeight")
            add("-Dglfwstub.initEgl=false")
            add("-Djava.library.path=${nativesDir.absolutePath}:${nativeDir}:${buildLibraryPath(javaHome)}")
            add("-Dorg.lwjgl.opengl.libname=libgl4es_114.so")
            add("-Dorg.lwjgl.openal.libname=$nativeDir/libopenal.so")
            add("-Djna.boot.library.path=$nativeDir")
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

    private fun buildLibraryPath(javaHome: File): String {
        val parts = mutableListOf<String>()
        val nativeDir = context.applicationInfo.nativeLibraryDir
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
        parts += nativeDir
        parts += "/system/lib64"
        parts += "/system/lib"
        parts += "/vendor/lib64"
        parts += "/vendor/lib"
        return parts.joinToString(":")
    }
}
