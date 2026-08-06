package com.booxin.launcher.core.launch

import android.util.Log
import android.view.Surface
import com.booxin.launcher.core.runtime.GameRuntimeBackends
import com.booxin.runtime.BooxinBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** :game 进程的 Surface，绑窗前必须就绪。 */
object GameSurfaceBridge {

    private const val TAG = "GameSurfaceBridge"

    class SurfaceLostException : Exception("Surface destroyed while waiting")

    @Volatile
    private var surface: Surface? = null

    @Volatile
    var width: Int = 0
        private set

    @Volatile
    var height: Int = 0
        private set

    private val lock = Any()
    private var waiters = mutableListOf<CompletableDeferred<Surface>>()

    @Volatile
    var onSurfaceLostWhileRunning: (() -> Unit)? = null

    fun onSurfaceCreated(surface: Surface) {
        synchronized(lock) {
            this.surface = surface
            Log.i(TAG, "created valid=${surface.isValid}")
            val pending = waiters
            waiters = mutableListOf()
            pending.forEach { d ->
                if (!d.isCompleted) d.complete(surface)
            }
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
        val running = LaunchSession.hotspotEntered ||
            LaunchSession.current() == LaunchPhase.Running
        synchronized(lock) {
            Log.i(TAG, "destroyed (was=${surface != null}) running=$running")
            surface = null
            val pending = waiters
            waiters = mutableListOf()
            pending.forEach { d ->
                if (!d.isCompleted) {
                    d.completeExceptionally(SurfaceLostException())
                }
            }
        }
        if (running) {
            onSurfaceLostWhileRunning?.invoke()
        } else {
            runCatching { NativeJvmLauncher.clearBridgeWindow() }
        }
    }

    fun hasSurface(): Boolean {
        val s = surface
        return s != null && s.isValid
    }

    fun currentSurface(): Surface? {
        val s = surface
        return if (s != null && s.isValid) s else null
    }

    suspend fun awaitValidSurface(timeoutMs: Long = 90_000L): Surface {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentSurface()?.let { return it }
            val deferred = CompletableDeferred<Surface>()
            synchronized(lock) {
                currentSurface()?.let { return it }
                waiters.add(deferred)
            }
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            runCatching {
                withTimeout(minOf(remaining, 2_000L)) { deferred.await() }
            }
            currentSurface()?.let { return it }
            synchronized(lock) { waiters.remove(deferred) }
            delay(50)
        }
        error("Surface timeout after ${timeoutMs}ms")
    }

    @Deprecated("Use awaitValidSurface", ReplaceWith("awaitValidSurface(timeoutMs)"))
    suspend fun awaitSurface(timeoutMs: Long = 60_000L): Surface = awaitValidSurface(timeoutMs)

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
