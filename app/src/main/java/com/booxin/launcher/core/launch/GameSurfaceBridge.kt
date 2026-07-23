package com.booxin.launcher.core.launch

import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Shares the Android [Surface] between [com.booxin.launcher.ui.launch.LaunchActivity]
 * and the JVM in the same `:game` process. pojavexec needs setupBridgeWindow() before GLFW
 * creates a window (otherwise ANativeWindow_acquire SIGSEGV).
 */
object GameSurfaceBridge {

    @Volatile
    private var surface: Surface? = null

    private val ready = CompletableDeferred<Surface>()

    fun onSurfaceCreated(surface: Surface) {
        this.surface = surface
        if (!ready.isCompleted) {
            ready.complete(surface)
        }
    }

    fun onSurfaceDestroyed() {
        surface = null
    }

    fun hasSurface(): Boolean = surface != null

    suspend fun awaitSurface(timeoutMs: Long = 60_000L): Surface {
        surface?.let { return it }
        return withTimeout(timeoutMs) { ready.await() }
    }

    fun attachToGlfw(surface: Surface) {
        if (!NativeJvmLauncher.setupBridgeWindow(surface)) {
            error("native setupBridgeWindow failed")
        }
    }
}
