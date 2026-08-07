package com.booxin.launcher.core

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where the launcher stores versions / libraries / assets / java / runtime.
 * Switching does not migrate existing files.
 */
enum class GameDirLocation(val prefValue: String) {
    /** App private: filesDir/minecraft */
    INTERNAL("internal"),

    /** App-specific external: Android/data/.../files/minecraft */
    EXTERNAL_APP("external_app"),

    /** Public: /sdcard/games/Booxin (may need storage access on some devices) */
    PUBLIC_GAMES("public_games"),

    /** Absolute path from prefs */
    CUSTOM("custom");

    companion object {
        fun fromPref(raw: String?): GameDirLocation =
            entries.firstOrNull { it.prefValue == raw } ?: INTERNAL
    }

    fun resolve(context: Context, customPath: String?): File {
        return when (this) {
            INTERNAL -> File(context.filesDir, "minecraft")
            EXTERNAL_APP -> {
                val base = context.getExternalFilesDir(null)
                    ?: File(context.filesDir, "external-fallback")
                File(base, "minecraft")
            }
            PUBLIC_GAMES -> File(
                Environment.getExternalStorageDirectory(),
                "games/Booxin"
            )
            CUSTOM -> {
                val path = customPath?.trim().orEmpty()
                if (path.isEmpty()) File(context.filesDir, "minecraft")
                else File(path)
            }
        }
    }

    fun labelRes(): Int = when (this) {
        INTERNAL -> com.booxin.launcher.R.string.settings_game_dir_internal
        EXTERNAL_APP -> com.booxin.launcher.R.string.settings_game_dir_external
        PUBLIC_GAMES -> com.booxin.launcher.R.string.settings_game_dir_public
        CUSTOM -> com.booxin.launcher.R.string.settings_game_dir_custom
    }
}
