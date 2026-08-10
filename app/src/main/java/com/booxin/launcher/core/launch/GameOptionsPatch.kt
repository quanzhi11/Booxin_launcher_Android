package com.booxin.launcher.core.launch

import android.util.Log
import com.booxin.launcher.core.LauncherPrefs
import java.io.File

/**
 * Patch options.txt: window size, launcher graphics/audio prefs, light FPS unlock.
 * Without width/height override, menu hover/click can miss.
 */
object GameOptionsPatch {
    private const val TAG = "BooxinLaunch"
    /** Minecraft treats >= 260 as unlimited. */
    private const val UNLIMITED_FPS = 260

    fun applyWindowOverrides(gameDir: File, width: Int, height: Int) {
        if (width < 2 || height < 2) return
        val file = File(gameDir, "options.txt")
        val map = linkedMapOf<String, String>()
        if (file.isFile) {
            file.forEachLine { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    map[line.substring(0, idx)] = line.substring(idx + 1)
                }
            }
        }
        map["fullscreen"] = "false"
        map["overrideWidth"] = width.toString()
        map["overrideHeight"] = height.toString()
        applyLauncherPrefs(map)
        applyFpsBoost(map)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(map.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n")
            Log.i(
                TAG,
                "options.txt ${width}x${height} fullscreen=false " +
                    "vsync=${map["enableVsync"]} maxFps=${map["maxFps"]} " +
                    "rd=${map["renderDistance"]} at ${file.absolutePath}"
            )
        }.onFailure {
            Log.w(TAG, "options.txt patch failed: ${it.message}")
        }
    }

    private fun applyLauncherPrefs(map: MutableMap<String, String>) {
        map["renderDistance"] = LauncherPrefs.renderDistance().toString()
        map["fancyGraphics"] = LauncherPrefs.fancyGraphics().toString()
        val volume = (LauncherPrefs.masterVolumePercent() / 100.0).coerceIn(0.0, 1.0)
        map["soundCategory_master"] = String.format(java.util.Locale.US, "%.2f", volume)
    }

    /**
     * Respect user vsync; when off, raise a finite maxFps cap slightly for headroom.
     */
    private fun applyFpsBoost(map: MutableMap<String, String>) {
        val vsync = LauncherPrefs.enableVsync()
        map["enableVsync"] = vsync.toString()
        map["vsync"] = vsync.toString()
        if (vsync) return

        val raw = map["maxFps"]
        val current = raw?.toIntOrNull()
        val boosted = when {
            current == null -> 120
            current >= UNLIMITED_FPS -> UNLIMITED_FPS
            else -> (current + 10).coerceAtMost(UNLIMITED_FPS)
        }
        map["maxFps"] = boosted.toString()
    }
}
