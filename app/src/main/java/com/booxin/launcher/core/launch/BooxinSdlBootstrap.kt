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
 * ART IO 协程栈太小，直接 System.loadLibrary(SDL3) 会 SIGSEGV。
 * 这里用 16MB 栈线程做 System.load（JNI_OnLoad 才能 FindClass 到
 * org.libsdl.app.*），再 setupJNI / Surface；HotSpot 侧只 dlopen+SetMainReady。
 */
object BooxinSdlBootstrap {
    private const val TAG = "BooxinSdl"
    private const val LOAD_STACK = 16L * 1024L * 1024L

    @Volatile private var surfaceBound = false
    private val libraryReady = AtomicBoolean(false)

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
        val density = dm.density
        val refresh = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                activity.display?.refreshRate ?: 60f
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.refreshRate
            }
        }.getOrDefault(60f)

        step(activity, "SDL: 大栈线程 load ${sdl.name}…")
        val err = arrayOfNulls<Throwable>(1)
        val loader = Thread(
            null,
            {
                try {
                    if (!libraryReady.get()) {
                        System.load(sdl.absolutePath)
                        NativeJvmLauncher.markSdlJniOnLoadDone()
                        libraryReady.set(true)
                    }
                    SDL.setupJNI()
                    SDLActivity.nativeSetScreenResolution(
                        width, height, width, height, density, refresh
                    )
                    SDLActivity.onNativeResize()
                    SDLActivity.onNativeSurfaceCreated()
                    SDLActivity.onNativeSurfaceChanged()
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
            step(activity, "SDL: load 失败 ${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        }
        step(activity, "SDL: ART load + setupJNI + Surface 就绪")
    }

    fun isSurfaceBound(): Boolean = surfaceBound

    fun isLibraryReady(): Boolean = libraryReady.get()

    /**
     * Called from HotSpot via ART if ART-side load did not run.
     * Must execute as a Java→JNI frame so SDL JNI_OnLoad can FindClass app classes.
     */
    @JvmStatic
    fun finishSdlAndroidInitFromArt(): Boolean {
        return runCatching {
            if (!libraryReady.get()) {
                val path = File(AndroidGameRuntime.nativesDir(), "libSDL3.so").absolutePath
                // Prefer native path that calls JNI_OnLoad under this Java frame.
                if (!NativeJvmLauncher.finishSdlJniOnLoad()) {
                    System.load(path)
                }
                libraryReady.set(true)
            }
            SDL.setupJNI()
            SDLActivity.onNativeSurfaceCreated()
            SDLActivity.onNativeSurfaceChanged()
            true
        }.getOrElse {
            Log.e(TAG, "finishSdlAndroidInitFromArt failed", it)
            false
        }
    }

    private fun step(activity: Activity, msg: String) {
        Log.i(TAG, msg)
        runCatching { GameLaunchLogBus.emit(activity.applicationContext, msg) }
    }
}
