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

    fun clearBridgeWindow() = nativeClearBridgeWindow()

    fun initializeHooks(): Boolean = nativeInitializeHooks()

    fun launchJvm(args: Array<String>, majorVersion: Int): Int {
        val full = "$majorVersion.0.1-internal"
        val dot = "$majorVersion.0.1"
        return nativeLaunchJvm(args, full, dot)
    }

    fun launchToolJvm(args: Array<String>): Int = nativeLaunchToolJvm(args)

    fun chdir(path: String): Boolean = nativeChdir(path)

    fun dumpInputBridge(): String = nativeDumpInputBridge() ?: "null"

    fun markMousePositionDirty() = nativeMarkMousePositionDirty()

    fun forcePumpInput(): Boolean = nativeForcePumpInput()

    fun invokeCursorPosCallback(x: Float, y: Float): Boolean = nativeInvokeCursorPosCallback(x, y)

    fun invokeMouseButtonCallback(button: Int, action: Int, mods: Int): Boolean =
        nativeInvokeMouseButtonCallback(button, action, mods)

    private external fun nativeChdir(path: String): Boolean

    private external fun nativeProbeJvm(): Boolean

    private external fun nativeDlopen(absolutePath: String): Boolean

    private external fun nativeSetupBridgeWindow(surface: Surface): Boolean

    private external fun nativeClearBridgeWindow()

    private external fun nativeInitializeHooks(): Boolean

    private external fun nativeDumpInputBridge(): String?

    private external fun nativeMarkMousePositionDirty()

    private external fun nativeForcePumpInput(): Boolean

    private external fun nativeInvokeCursorPosCallback(x: Float, y: Float): Boolean

    private external fun nativeInvokeMouseButtonCallback(button: Int, action: Int, mods: Int): Boolean

    private external fun nativeLaunchJvm(
        args: Array<String>,
        fullVersion: String,
        dotVersion: String
    ): Int

    private external fun nativeLaunchToolJvm(args: Array<String>): Int
}
