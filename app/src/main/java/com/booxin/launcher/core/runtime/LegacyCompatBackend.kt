package com.booxin.launcher.core.runtime

import android.content.Context
import android.view.Surface
import com.booxin.launcher.core.java.InstalledJavaRuntime
import com.booxin.launcher.core.launch.AndroidGameRuntime
import com.booxin.launcher.core.launch.JvmEnvironment
import com.booxin.launcher.core.launch.LaunchCommand
import com.booxin.launcher.core.launch.NativeJvmLauncher
import com.booxin.runtime.BooxinBridge

/**
 * First [GameRuntimeBackend] implementation: wraps transitional Pojav/FCL natives
 * behind the Booxin API. Replace with [BooxinNativeBackend] when self-hosted bridge ships.
 */
object LegacyCompatBackend : GameRuntimeBackend {
    override val id: String = "legacy-compat"

    override fun prepare(context: Context) {
        AndroidGameRuntime.ensure(context)
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
