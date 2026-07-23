package com.booxin.launcher.core.launch

import java.io.File

/**
 * Loads a single [libpojavexec.so] from staged natives (not APK nativeLibraryDir).
 *
 * Loading both paths creates two copies with separate `pojav_environ` / `br_init`,
 * so ART setupBridgeWindow and HotSpot glfwInit disagree and crash.
 */
object PojavExecLoader {

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val staged = File(AndroidGameRuntime.nativesDir(), "libpojavexec.so")
            require(staged.isFile) {
                "缺少 staged libpojavexec.so: ${staged.absolutePath}"
            }
            // Absolute System.load so linker does not also pull APK lib/arm64 copy.
            System.load(staged.absolutePath)
            loaded = true
        }
    }
}
