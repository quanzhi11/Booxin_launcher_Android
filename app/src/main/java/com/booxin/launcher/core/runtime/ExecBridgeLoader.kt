package com.booxin.launcher.core.runtime

import android.util.Log
import com.booxin.launcher.BooxinApp
import com.booxin.launcher.core.launch.AndroidGameRuntime
import java.io.File

/**
 * Loads the staged input/GL bridge once.
 *
 * LWJGL looks up a fixed bridge soname, so we stage our library under that name.
 * ART and HotSpot must map the same file — prefer the app-private staged copy.
 *
 * Shared-storage absolute paths fail with classloader-namespace; staging lives
 * under [LauncherPaths.runtimeDir] (app filesDir), with APK nativeLibraryDir
 * as fallback.
 */
object ExecBridgeLoader {

    private const val TAG = "ExecBridgeLoader"
    private const val LWJGL_SONAME = "libpojavexec.so"
    private const val BOOXIN_SONAME = "libbooxin_bridge.so"
    private const val BOOXIN_LIB_NAME = "booxin_bridge"

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val errors = mutableListOf<String>()

            // 1) App-private staged natives (same path HotSpot will System.load).
            val dir = AndroidGameRuntime.nativesDir()
            val staged = listOf(File(dir, LWJGL_SONAME), File(dir, BOOXIN_SONAME))
            for (file in staged) {
                if (!file.isFile) continue
                runCatching {
                    System.load(file.absolutePath)
                    loaded = true
                    Log.i(TAG, "loaded staged: ${file.absolutePath}")
                    return
                }.onFailure { errors += "${file.name}: ${it.message}" }
            }

            // 2) APK-extracted library (classloader namespace always allows this).
            runCatching {
                System.loadLibrary(BOOXIN_LIB_NAME)
                loaded = true
                Log.i(TAG, "loaded via System.loadLibrary($BOOXIN_LIB_NAME)")
                return
            }.onFailure { errors += "loadLibrary: ${it.message}" }

            val apkDir = runCatching {
                BooxinApp.getAppContext().applicationInfo.nativeLibraryDir
            }.getOrNull()
            if (!apkDir.isNullOrBlank()) {
                val apkBridge = File(apkDir, BOOXIN_SONAME)
                if (apkBridge.isFile) {
                    runCatching {
                        System.load(apkBridge.absolutePath)
                        loaded = true
                        Log.i(TAG, "loaded APK path: ${apkBridge.absolutePath}")
                        return
                    }.onFailure { errors += "apkPath: ${it.message}" }
                }
            }

            error(
                "exec bridge 加载失败（staged=${dir.absolutePath}）: " +
                    errors.joinToString(" | ")
            )
        }
    }

    fun isLoaded(): Boolean = loaded
}
