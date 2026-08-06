package com.booxin.launcher.core.launch

import android.app.Activity
import android.util.Log
import android.view.Surface
import com.booxin.launcher.core.java.MinecraftJavaRequirement
import org.libsdl.app.SDL
import org.libsdl.app.SDLActivity
import java.io.File

/** ART 侧加载官方 SDL3，并绑上 SurfaceView。 */
object BooxinSdlBootstrap {
    private const val TAG = "BooxinSdl"
    @Volatile private var prepared = false

    fun maybePrepare(activity: Activity, versionId: String, surface: Surface, width: Int, height: Int) {
        if (!MinecraftJavaRequirement.usesSdlWindowing(versionId)) return
        val sdl = File(AndroidGameRuntime.nativesDir(), "libSDL3.so")
        if (!sdl.isFile) {
            step(activity, "missing ${sdl.absolutePath}")
            return
        }
        // Stepwise so a native crash leaves a clear last-line in launch log.
        if (!prepared) {
            Class.forName("org.libsdl.app.HIDDeviceManager")
            Class.forName("org.libsdl.app.SDLAudioManager")
            Class.forName("org.libsdl.app.SDLControllerManager")
            step(activity, "SDL step: loadLibrary…")
            runCatching { System.loadLibrary("SDL3") }
                .recoverCatching { System.load(sdl.absolutePath) }
                .getOrThrow()
            step(activity, "SDL step: setContext…")
            SDL.setContext(activity.applicationContext)
            step(activity, "SDL step: setupJNI…")
            SDL.setupJNI()
            prepared = true
            step(activity, "SDL step: setupJNI OK")
        }
        step(activity, "SDL step: attachSurface…")
        SDLActivity.booxinAttachSurface(activity, surface)
        val dm = activity.resources.displayMetrics
        val density = dm.density
        val rate = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                activity.display?.refreshRate ?: 60f
            } else {
                @Suppress("DEPRECATION")
                (activity.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.refreshRate
            }
        }.getOrDefault(60f)
        step(activity, "SDL step: setResolution ${width}x${height}…")
        SDLActivity.nativeSetScreenResolution(width, height, width, height, density, rate)
        step(activity, "SDL step: onNativeSurfaceCreated…")
        SDLActivity.onNativeSurfaceCreated()
        step(activity, "SDL step: onNativeSurfaceChanged…")
        SDLActivity.onNativeSurfaceChanged()
        step(activity, "SDL step: surface bound OK")
    }

    private fun step(activity: Activity, msg: String) {
        Log.i(TAG, msg)
        runCatching { GameLaunchLogBus.emit(activity.applicationContext, msg) }
    }
}
