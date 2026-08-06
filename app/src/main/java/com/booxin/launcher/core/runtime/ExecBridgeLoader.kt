package com.booxin.launcher.core.runtime

import com.booxin.launcher.core.launch.AndroidGameRuntime
import java.io.File

/**
 * Loads the staged input/GL bridge once.
 *
 * Android LWJGL still asks for `libpojavexec.so`, so we stage our bridge under
 * ART 与 HotSpot 必须映射同一份。
 */
object ExecBridgeLoader {

    private const val LWJGL_SONAME = "libpojavexec.so"
    private const val BOOXIN_SONAME = "libbooxin_bridge.so"

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val dir = AndroidGameRuntime.nativesDir()
            val lwjglName = File(dir, LWJGL_SONAME)
            val booxinName = File(dir, BOOXIN_SONAME)
            val file = when {
                lwjglName.isFile -> lwjglName
                booxinName.isFile -> booxinName
                else -> error("缺少 staged exec bridge: ${lwjglName.absolutePath}")
            }
            System.load(file.absolutePath)
            loaded = true
        }
    }

    fun isLoaded(): Boolean = loaded
}
