package com.booxin.launcher.core.launch

import android.app.Activity
import android.util.Log
import android.view.Surface
import com.booxin.launcher.core.java.MinecraftJavaRequirement
import org.libsdl.app.SDL
import org.libsdl.app.SDLActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SDL3 Android init for 26.3+.
 *
 * Do NOT System.load(libSDL3) before HotSpot JNI_CreateJavaVM — on this OEM that
 * combination exits the :game process with code 1 within ~100ms. Attach the
 * Surface early; finishLibraryLoad runs after JVM created (finishSdlAndroidInitFromArt).
 */
object BooxinSdlBootstrap {
    private const val TAG = "BooxinSdl"
    private const val LOAD_STACK = 16L * 1024L * 1024L

    @Volatile private var surfaceBound = false
    @Volatile private var pendingWidth = 0
    @Volatile private var pendingHeight = 0
    @Volatile private var pendingDensity = 1f
    @Volatile private var pendingRefresh = 60f
    private val libraryReady = AtomicBoolean(false)

    /**
     * Pre-JVM: bind Surface + ClassLoader only. Library load is deferred.
     */
    fun maybePrepare(activity: Activity, versionId: String, surface: Surface, width: Int, height: Int) {
        if (!MinecraftJavaRequirement.usesSdlWindowing(versionId)) return
        val sdl = File(AndroidGameRuntime.nativesDir(), "libSDL3.so")
        if (!sdl.isFile) {
            step(activity, "SDL: 缺少 ${sdl.absolutePath}")
            return
        }
        val app = activity.applicationContext
        SDL.setContext(activity)
        SDLActivity.booxinAttachSurface(app, surface)
        surfaceBound = true

        runCatching {
            NativeJvmLauncher.cacheArtClassLoader(BooxinSdlBootstrap::class.java.classLoader)
        }.onFailure {
            Log.w(TAG, "cacheArtClassLoader: ${it.message}")
        }

        val dm = app.resources.displayMetrics
        pendingDensity = dm.density
        pendingWidth = width.coerceAtLeast(1)
        pendingHeight = height.coerceAtLeast(1)
        pendingRefresh = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                activity.display?.refreshRate ?: 60f
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.refreshRate
            }
        }.getOrDefault(60f)

        step(activity, "SDL: Surface 已附着（库加载推迟到 JVM 创建后，避免 CreateJavaVM exit）")
    }

    fun isSurfaceBound(): Boolean = surfaceBound

    fun isLibraryReady(): Boolean = libraryReady.get()

    /**
     * HotSpot cannot FindClass(SDLActivity). Call from ART after DestroyWindow
     * so SDL can fetch the external Surface again for the real game window.
     */
    @JvmStatic
    fun resyncNativeSurface(): Boolean {
        return runCatching {
            if (!libraryReady.get()) {
                Log.w(TAG, "resyncNativeSurface: SDL not loaded")
                return false
            }
            SDLActivity.onNativeSurfaceCreated()
            SDLActivity.onNativeSurfaceChanged()
            Log.i(TAG, "resyncNativeSurface ok")
            true
        }.getOrElse {
            Log.e(TAG, "resyncNativeSurface failed", it)
            false
        }
    }

    /** Re-bind TextureView Surface then notify SDL (resume path). */
    @JvmStatic
    fun reattachSurface(context: android.content.Context, surface: Surface): Boolean {
        return runCatching {
            SDLActivity.booxinAttachSurface(context, surface)
            surfaceBound = true
            resyncNativeSurface()
        }.getOrElse {
            Log.e(TAG, "reattachSurface failed", it)
            false
        }
    }

    /** Pause / Surface lost: clear external Surface pointer only.
     * Do NOT call onNativeSurfaceDestroyed — SDL treats that as window teardown
     * and Minecraft often exits, which sends the user back to MainActivity. */
    fun notifySurfaceLost() {
        runCatching { SDLActivity.booxinDetachSurface() }
        surfaceBound = false
        Log.i(TAG, "notifySurfaceLost (pointer cleared, SDL window kept)")
    }

    /**
     * Called from HotSpot via ART after CreateJavaVM.
     * Must execute as a Java→JNI frame so SDL JNI_OnLoad can FindClass app classes.
     */
    @JvmStatic
    fun finishSdlAndroidInitFromArt(): Boolean {
        return runCatching {
            loadSdlLibraryUnderJavaFrame()
            SDL.setupJNI()
            val w = pendingWidth.coerceAtLeast(1)
            val h = pendingHeight.coerceAtLeast(1)
            runCatching {
                val natural = SDLActivity.getNaturalOrientation()
                val rotation = SDLActivity.getCurrentRotation()
                SDLActivity.nativeSetNaturalOrientation(natural)
                SDLActivity.onNativeRotationChanged(rotation)
                SDLActivity.nativeSetScreenResolution(
                    w, h, w, h, pendingDensity, pendingRefresh
                )
                SDLActivity.onNativeResize()
                Log.i(TAG, "SDL orientation natural=$natural rotation=$rotation ${w}x${h}")
            }
            SDLActivity.onNativeSurfaceCreated()
            SDLActivity.onNativeSurfaceChanged()
            Log.i(TAG, "finishSdlAndroidInitFromArt ok ${w}x${h}")
            true
        }.getOrElse {
            Log.e(TAG, "finishSdlAndroidInitFromArt failed", it)
            false
        }
    }

    private fun loadSdlLibraryUnderJavaFrame() {
        if (libraryReady.get()) return
        val path = File(AndroidGameRuntime.nativesDir(), "libSDL3.so").absolutePath
        val err = arrayOfNulls<Throwable>(1)
        val loader = Thread(
            null,
            {
                try {
                    if (!NativeJvmLauncher.finishSdlJniOnLoad()) {
                        System.load(path)
                        NativeJvmLauncher.markSdlJniOnLoadDone()
                    }
                    libraryReady.set(true)
                } catch (t: Throwable) {
                    err[0] = t
                }
            },
            "booxin-sdl-load",
            LOAD_STACK
        )
        loader.start()
        loader.join()
        val failure = err[0]
        if (failure != null) {
            libraryReady.set(false)
            throw failure
        }
    }

    private fun step(activity: Activity, msg: String) {
        Log.i(TAG, msg)
        runCatching { GameLaunchLogBus.emit(activity.applicationContext, msg) }
    }
}
