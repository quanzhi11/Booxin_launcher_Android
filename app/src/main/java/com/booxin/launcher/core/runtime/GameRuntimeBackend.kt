package com.booxin.launcher.core.runtime

import android.content.Context
import android.view.Surface
import com.booxin.launcher.core.java.InstalledJavaRuntime
import com.booxin.launcher.core.launch.LaunchCommand

/** How the launcher talks to the game process (surface, input, JVM env, launch). */
interface GameRuntimeBackend {
    val id: String

    fun prepare(context: Context)

    fun ensureExecBridgeLoaded(skipArtPreload: Boolean): Result<Unit>

    fun attachSurface(surface: Surface): Boolean

    fun enableInput(): Boolean

    fun setInputReady(ready: Boolean)

    fun applyJvmEnvironment(
        context: Context,
        java: InstalledJavaRuntime,
        extraEnv: Map<String, String>
    )

    fun launch(command: LaunchCommand, javaMajor: Int): Int
}

object GameRuntimeBackends {
    @Volatile
    private var active: GameRuntimeBackend = BooxinNativeBackend

    fun current(): GameRuntimeBackend = active

    fun install(backend: GameRuntimeBackend) {
        active = backend
    }
}
