package com.booxin.launcher.core.launch

object NativeJvmLauncher {
    init {
        System.loadLibrary("booxin_jvm")
    }

    fun preloadLibrary(absolutePath: String): Boolean {
        return nativeDlopen(absolutePath)
    }

    fun probeJvm(): Boolean = nativeProbeJvm()

    fun launchJvm(args: Array<String>, majorVersion: Int): Int {
        val full = "$majorVersion.0.1-internal"
        val dot = "$majorVersion.0.1"
        return nativeLaunchJvm(args, full, dot)
    }

    fun chdir(path: String): Boolean = nativeChdir(path)

    private external fun nativeChdir(path: String): Boolean

    private external fun nativeProbeJvm(): Boolean

    private external fun nativeDlopen(absolutePath: String): Boolean

    private external fun nativeLaunchJvm(
        args: Array<String>,
        fullVersion: String,
        dotVersion: String
    ): Int
}
