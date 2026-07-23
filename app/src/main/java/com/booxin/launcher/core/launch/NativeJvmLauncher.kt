package com.booxin.launcher.core.launch

import android.view.Surface

object NativeJvmLauncher {
    init {
        System.loadLibrary("booxin_jvm")
    }

    fun preloadLibrary(absolutePath: String): Boolean {
        return nativeDlopen(absolutePath)
    }

    fun probeJvm(): Boolean = nativeProbeJvm()

    fun setupBridgeWindow(surface: Surface): Boolean = nativeSetupBridgeWindow(surface)

    fun initializeHooks(): Boolean = nativeInitializeHooks()

    fun launchJvm(args: Array<String>, majorVersion: Int): Int {
        val full = "$majorVersion.0.1-internal"
        val dot = "$majorVersion.0.1"
        return nativeLaunchJvm(args, full, dot)
    }

    fun chdir(path: String): Boolean = nativeChdir(path)

    /** Snapshot pojav_environ input gates (ready / callbacks / queue). */
    fun dumpInputBridge(): String = nativeDumpInputBridge() ?: "null"

    private external fun nativeChdir(path: String): Boolean

    private external fun nativeProbeJvm(): Boolean

    private external fun nativeDlopen(absolutePath: String): Boolean

    private external fun nativeSetupBridgeWindow(surface: Surface): Boolean

    private external fun nativeInitializeHooks(): Boolean

    private external fun nativeDumpInputBridge(): String?

    private external fun nativeLaunchJvm(
        args: Array<String>,
        fullVersion: String,
        dotVersion: String
    ): Int
}
