package com.booxin.launcher.core.launch

import android.util.Log
import android.view.Surface
import com.booxin.launcher.core.runtime.GameRuntimeBackends
import com.booxin.runtime.BooxinBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

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

    /** Called when Surface dies while the game JVM is running (background / screen off). */
    @Volatile
    var onSurfaceLostWhileRunning: (() -> Unit)? = null

    /** Called when a new Surface appears while the game JVM is still running. */
    @Volatile
    var onSurfaceRestoredWhileRunning: (() -> Unit)? = null

    @Volatile
    var surfacePaused: Boolean = false
        private set

    /** Bumps on each pause/resume so stale rebind loops exit. */
    private val rebindEpoch = AtomicInteger(0)

    fun currentRebindEpoch(): Int = rebindEpoch.get()

    private fun isGameRunning(): Boolean =
        LaunchSession.hotspotEntered || LaunchSession.current() == LaunchPhase.Running

    fun onSurfaceCreated(surface: Surface) {
        val running = isGameRunning()
        synchronized(lock) {
            this.surface = surface
            Log.i(TAG, "created valid=${surface.isValid} running=$running paused=$surfacePaused")
            val pending = waiters
            waiters = mutableListOf()
            pending.forEach { d ->
                if (!d.isCompleted) d.complete(surface)
            }
        }
        // Every Surface available while JVM is running → setupBridgeWindow again
        // (covers resume with or without an intervening destroy callback).
        if (running) {
            onSurfaceRestoredWhileRunning?.invoke()
        } else {
            surfacePaused = false
        }
    }

    fun onSurfaceSizeChanged(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val firstSize = width <= 1 || height <= 1
        width = w
        height = h
        BooxinBridge.setWindowSize(w, h)
        runCatching { BooxinBridge.sendUpdateWindowSize(w, h) }
        // Size often arrives after create on resume — kick rebind once dims are real.
        if (firstSize && isGameRunning() && surfacePaused) {
            onSurfaceRestoredWhileRunning?.invoke()
        }
    }

    fun onSurfaceDestroyed() {
        val running = isGameRunning()
        rebindEpoch.incrementAndGet()
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
            surfacePaused = true
            // Detach EGL window surface + release ANativeWindow; keep GL context / JVM.
            runCatching { NativeJvmLauncher.clearBridgeWindow() }
            onSurfaceLostWhileRunning?.invoke()
        } else {
            surfacePaused = false
            runCatching { NativeJvmLauncher.clearBridgeWindow() }
        }
    }

    fun markRebound() {
        surfacePaused = false
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
            BooxinBridge.setWindowSize(width, height)
            runCatching { BooxinBridge.sendUpdateWindowSize(width, height) }
        }
        markRebound()
    }

    /** Best-effort rebind used after resume / Surface recreate. */
    fun rebindIfPossible(): Boolean {
        val surf = currentSurface() ?: return false
        return runCatching {
            attachToGlfw(surf)
            true
        }.getOrDefault(false)
    }
}
