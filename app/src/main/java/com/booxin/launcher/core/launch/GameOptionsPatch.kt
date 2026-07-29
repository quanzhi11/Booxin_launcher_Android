package com.booxin.launcher.core.launch

import android.util.Log
import java.io.File

/**
 * FCL [GameOption] equivalent: Minecraft reads overrideWidth/Height + fullscreen
 * for framebuffer / GUI hit-testing. Without this, menu hover/click can miss
 * even when GLFW cursor coords look correct.
 */
object GameOptionsPatch {
    private const val TAG = "BooxinLaunch"

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
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(map.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n")
            Log.i(TAG, "options.txt override ${width}x${height} fullscreen=false at ${file.absolutePath}")
        }.onFailure {
            Log.w(TAG, "options.txt patch failed: ${it.message}")
        }
    }
}
