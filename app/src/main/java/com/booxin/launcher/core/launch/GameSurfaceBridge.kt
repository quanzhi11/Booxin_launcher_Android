package com.booxin.launcher.core.launch

import android.view.Surface
import com.booxin.launcher.core.runtime.GameRuntimeBackends
import com.booxin.runtime.BooxinBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Surface for the :game process — bind before GLFW or ANativeWindow_acquire crashes. */
object GameSurfaceBridge {

    @Volatile
    private var surface: Surface? = null

    @Volatile
    var width: Int = 0
        private set

    @Volatile
    var height: Int = 0
        private set

    private val ready = CompletableDeferred<Surface>()

    fun onSurfaceCreated(surface: Surface) {
        this.surface = surface
        if (!ready.isCompleted) {
            ready.complete(surface)
        }
    }

    fun onSurfaceSizeChanged(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        width = w
        height = h
        BooxinBridge.setWindowSize(w, h)
        runCatching { BooxinBridge.sendUpdateWindowSize(w, h) }
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
        val backend = GameRuntimeBackends.current()
        if (!backend.attachSurface(surface)) {
            error("native setupBridgeWindow failed")
        }
        if (width > 0 && height > 0) {
            onSurfaceSizeChanged(width, height)
        }
    }
}
