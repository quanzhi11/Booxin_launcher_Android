package com.booxin.launcher.core.runtime

import android.content.Context
import android.util.Log
import android.view.Surface
import com.booxin.launcher.core.java.InstalledJavaRuntime
import com.booxin.launcher.core.launch.AndroidGameRuntime
import com.booxin.launcher.core.launch.JvmEnvironment
import com.booxin.launcher.core.launch.LaunchCommand
import com.booxin.launcher.core.launch.NativeJvmLauncher
import com.booxin.runtime.BooxinBridge

/** Default game runtime: staged natives + Booxin bridge. */
object BooxinNativeBackend : GameRuntimeBackend {
    private const val TAG = "BooxinNativeBackend"

    override val id: String = "booxin-native"

    override fun prepare(context: Context) {
        AndroidGameRuntime.ensure(context)
        Log.i(TAG, "prepare: staging natives under ${AndroidGameRuntime.nativesDir()}")
    }

    override fun ensureExecBridgeLoaded(skipArtPreload: Boolean): Result<Unit> {
        if (skipArtPreload) return Result.success(Unit)
        return runCatching { ExecBridgeLoader.ensureLoaded() }
    }

    override fun attachSurface(surface: Surface): Boolean {
        return NativeJvmLauncher.setupBridgeWindow(surface)
    }

    override fun enableInput(): Boolean = BooxinBridge.enableAndroidInput()

    override fun setInputReady(ready: Boolean) {
        BooxinBridge.setInputReady(ready)
    }

    override fun applyJvmEnvironment(
        context: Context,
        java: InstalledJavaRuntime,
        extraEnv: Map<String, String>
    ) {
        JvmEnvironment.apply(context, java, extraEnv)
    }

    override fun launch(command: LaunchCommand, javaMajor: Int): Int {
        val argv = command.asProcessCommand().toMutableList()
        argv[0] = "java"
        return NativeJvmLauncher.launchJvm(argv.toTypedArray(), javaMajor)
    }
}
