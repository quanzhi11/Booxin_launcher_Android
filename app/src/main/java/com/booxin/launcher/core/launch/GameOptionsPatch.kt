package com.booxin.launcher.core.launch

import android.util.Log
import java.io.File

/**
 * Patch options.txt: window size + light FPS unlock.
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
        applyFpsBoost(map)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(map.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n")
            Log.i(
                TAG,
                "options.txt ${width}x${height} fullscreen=false " +
                    "vsync=${map["enableVsync"]} maxFps=${map["maxFps"]} at ${file.absolutePath}"
            )
        }.onFailure {
            Log.w(TAG, "options.txt patch failed: ${it.message}")
        }
    }

    /**
     * +10 FPS headroom: turn off in-game vsync and raise a finite maxFps cap by 10.
     */
    private fun applyFpsBoost(map: MutableMap<String, String>) {
        map["enableVsync"] = "false"
        // Some older builds also read this key.
        map["vsync"] = "false"

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
