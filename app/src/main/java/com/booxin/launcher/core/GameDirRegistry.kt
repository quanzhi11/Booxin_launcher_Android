package com.booxin.launcher.core

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Multi game-root registry.
 * Index 0 is always the original internal directory and cannot be removed.
 */
data class GameDirItem(
    val path: String,
    val isDefault: Boolean
)

object GameDirRegistry {

    private const val KEY_EXTRA_PATHS = "game_dir_extra_paths_json"
    private const val KEY_SELECTED_PATH = "game_dir_selected_path"

    fun defaultPath(context: Context): File =
        GameDirLocation.INTERNAL.resolve(context.applicationContext, null)

    fun list(context: Context): List<GameDirItem> {
        val app = context.applicationContext
        migrateLegacyIfNeeded(app)
        val def = defaultPath(app).absolutePath
        val extras = loadExtraPaths()
            .map { File(it).absolutePath }
            .filter { it.isNotBlank() && it != def }
            .distinct()
        return listOf(GameDirItem(def, isDefault = true)) +
            extras.map { GameDirItem(it, isDefault = false) }
    }

    fun selectedPath(context: Context): String {
        val items = list(context)
        val saved = LauncherPrefs.getString(KEY_SELECTED_PATH, "").orEmpty().trim()
        if (saved.isNotEmpty() && items.any { it.path == saved }) return saved
        return items.first().path
    }

    fun isSelected(context: Context, path: String): Boolean =
        selectedPath(context) == File(path).absolutePath

    fun addPath(context: Context, rawPath: String): Result<String> = runCatching {
        val app = context.applicationContext
        val path = File(rawPath.trim()).absolutePath
        require(path.isNotBlank()) { "路径为空" }
        require(File(path).isAbsolute) { "请使用绝对路径" }
        val def = defaultPath(app).absolutePath
        require(path != def) { "已是默认目录" }
        val extras = loadExtraPaths().toMutableList()
        if (extras.none { File(it).absolutePath == path }) {
            extras += path
            saveExtraPaths(extras)
        }
        path
    }

    fun removePath(context: Context, rawPath: String): Result<Unit> = runCatching {
        val app = context.applicationContext
        val path = File(rawPath).absolutePath
        val def = defaultPath(app).absolutePath
        require(path != def) { "默认目录不能删除" }
        val extras = loadExtraPaths()
            .map { File(it).absolutePath }
            .filter { it != path }
        saveExtraPaths(extras)
        if (selectedPath(app) == path) {
            LauncherPrefs.putString(KEY_SELECTED_PATH, def)
        }
    }

    fun selectPath(context: Context, rawPath: String): Result<File> = runCatching {
        val app = context.applicationContext
        val path = File(rawPath).absolutePath
        val items = list(app)
        require(items.any { it.path == path }) { "目录不在列表中" }
        LauncherPrefs.putString(KEY_SELECTED_PATH, path)
        if (path == defaultPath(app).absolutePath) {
            LauncherPrefs.setGameDir(GameDirLocation.INTERNAL, null)
        } else {
            LauncherPrefs.setGameDir(GameDirLocation.CUSTOM, path)
        }
        File(path)
    }

    private fun migrateLegacyIfNeeded(context: Context) {
        if (LauncherPrefs.getString(KEY_SELECTED_PATH, null) != null) return
        val loc = LauncherPrefs.gameDirLocation()
        val custom = LauncherPrefs.gameDirCustomPath()
        val resolved = loc.resolve(context, custom).absolutePath
        val def = defaultPath(context).absolutePath
        if (resolved != def) {
            val extras = loadExtraPaths().toMutableList()
            if (extras.none { File(it).absolutePath == resolved }) {
                extras += resolved
                saveExtraPaths(extras)
            }
        }
        LauncherPrefs.putString(KEY_SELECTED_PATH, resolved)
    }

    private fun loadExtraPaths(): List<String> {
        val raw = LauncherPrefs.getString(KEY_EXTRA_PATHS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) add(s)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveExtraPaths(paths: List<String>) {
        val arr = JSONArray()
        paths.distinct().forEach { arr.put(it) }
        LauncherPrefs.putString(KEY_EXTRA_PATHS, arr.toString())
    }
}
