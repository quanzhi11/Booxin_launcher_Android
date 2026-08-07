package com.booxin.launcher.core

import android.content.Context
import com.booxin.launcher.R
import java.io.File

/**
 * Central game storage paths for the launcher.
 */
object LauncherPaths {
    lateinit var rootDir: File
        private set

    val versionsDir: File get() = File(rootDir, "versions")
    val librariesDir: File get() = File(rootDir, "libraries")
    val assetsDir: File get() = File(rootDir, "assets")
    val accountsFile: File get() = File(rootDir, "accounts.json")

    /** Installed Java runtimes: java/java-8, java/java-17, ... */
    val javaDir: File get() = File(rootDir, "java")
    val javaCacheDir: File get() = File(rootDir, "cache/java")

    /** Android-patched LWJGL / JNA extracted from APK assets. */
    val runtimeDir: File get() = File(rootDir, "runtime")

    fun javaRuntimeDir(componentId: String): File = File(javaDir, componentId)

    val isInitialized: Boolean
        get() = ::rootDir.isInitialized

    fun init(context: Context) {
        val app = context.applicationContext
        val selected = GameDirRegistry.selectedPath(app)
        rootDir = File(selected).also { ensureWritableTree(it) }
    }

    /**
     * Switch active game root to an already-registered path.
     * Does not migrate data. Caller should restart the process after success.
     */
    fun switchToRegistered(context: Context, path: String): Result<File> = runCatching {
        val app = context.applicationContext
        val target = GameDirRegistry.selectPath(app, path).getOrThrow()
        ensureWritableTree(target)
        rootDir = target
        target
    }

    /**
     * Persist and apply a new game root by path (adds to registry if needed).
     * Does not migrate data. Caller should restart after success.
     */
    fun switchRoot(
        context: Context,
        location: GameDirLocation,
        customPath: String? = null
    ): Result<File> = runCatching {
        val app = context.applicationContext
        val target = location.resolve(app, customPath)
        if (location == GameDirLocation.CUSTOM) {
            val path = customPath?.trim().orEmpty()
            require(path.isNotEmpty()) { "自定义路径不能为空" }
            require(File(path).isAbsolute) { "请使用绝对路径" }
        }
        val abs = target.absolutePath
        if (abs != GameDirRegistry.defaultPath(app).absolutePath) {
            GameDirRegistry.addPath(app, abs).getOrThrow()
        }
        GameDirRegistry.selectPath(app, abs).getOrThrow()
        ensureWritableTree(target)
        rootDir = target
        target
    }

    fun currentLocationLabel(context: Context): String {
        val path = rootDir.absolutePath
        val def = GameDirRegistry.defaultPath(context).absolutePath
        val name = if (path == def) {
            context.getString(R.string.settings_game_dir_internal)
        } else {
            context.getString(R.string.settings_game_dir_custom)
        }
        return "$name\n$path"
    }

    fun ensureWritableTree(root: File) {
        if (!root.exists() && !root.mkdirs()) {
            error("无法创建目录: ${root.absolutePath}")
        }
        if (!root.isDirectory) {
            error("路径不是目录: ${root.absolutePath}")
        }
        val probe = File(root, ".booxin_write_probe")
        try {
            probe.writeText("ok")
            if (!probe.delete()) probe.deleteOnExit()
        } catch (t: Throwable) {
            throw IllegalStateException("目录不可写: ${root.absolutePath}", t)
        }
        File(root, "versions").mkdirs()
        File(root, "libraries").mkdirs()
        val assets = File(root, "assets").also { it.mkdirs() }
        File(assets, "indexes").mkdirs()
        File(assets, "objects").mkdirs()
        File(root, "java").mkdirs()
        File(root, "cache/java").mkdirs()
        File(root, "runtime").mkdirs()
        File(root, "logs").mkdirs()
    }
}
