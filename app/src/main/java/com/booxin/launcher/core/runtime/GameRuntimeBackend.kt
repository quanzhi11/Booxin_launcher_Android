package com.booxin.launcher.core.runtime

import android.content.Context
import android.view.Surface
import com.booxin.launcher.core.java.InstalledJavaRuntime
import com.booxin.launcher.core.launch.LaunchCommand

/**
 * Stable contract between launcher business code and the game process runtime.
 * Implementations may use transitional natives; callers must not depend on that.
 */
interface GameRuntimeBackend {
    val id: String

    fun prepare(context: Context)

    /** Load ART-side exec/input bridge when required for this loader/mainClass. */
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

    /** Swap implementation (tests / LegacyCompatBackend). */
    fun install(backend: GameRuntimeBackend) {
        active = backend
    }
}
