package com.booxin.launcher.core.runtime

import android.os.Build
import com.booxin.launcher.core.LauncherPrefs
import com.booxin.launcher.core.launch.GlRendererKind
import com.booxin.launcher.core.launch.GlRendererProfile

/** Picks GLES translator for a version id (honours user preference). */
object RendererBackend {
    fun kindForVersion(versionId: String): GlRendererKind {
        val preferred = LauncherPrefs.rendererKind()
        if (preferred != null) return preferred
        var auto = GlRendererProfile.forVersion(versionId)

        // Android 9 (Pie) compatibility:
        // GL4ES/opengles2 can fail at buffer mapping on some drivers,
        // causing "Can't map buffer, opengl error 0" then black-screen crash.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            auto == GlRendererKind.GL4ES
        ) {
            auto = GlRendererKind.MOBILE_GLUES
        }

        return auto
    }

    fun displayName(kind: GlRendererKind): String = RuntimeEnv.libGlString(kind)

    fun booxinRendererToken(kind: GlRendererKind): String = RuntimeEnv.rendererToken(kind)
}
