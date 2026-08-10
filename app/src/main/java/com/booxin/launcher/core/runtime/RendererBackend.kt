package com.booxin.launcher.core.runtime

import android.os.Build
import com.booxin.launcher.core.LauncherPrefs
import com.booxin.launcher.core.launch.GlRendererKind
import com.booxin.launcher.core.launch.GlRendererProfile
import com.booxin.launcher.core.launch.OemLaunchProfile

/** Picks GLES translator for a version id (honours user preference + mod profiles). */
object RendererBackend {
    fun kindForVersion(versionId: String): GlRendererKind =
        kindForLaunch(instanceVersionId = versionId, mcVersionId = versionId)

    /**
     * @param instanceVersionId folder under versions/ (has mods/)
     * @param mcVersionId resolved Minecraft version for age-based defaults
     */
    fun kindForLaunch(instanceVersionId: String, mcVersionId: String): GlRendererKind {
        val preferred = LauncherPrefs.rendererKind()
        var auto = GlRendererProfile.forVersion(mcVersionId)
        val profile = ModRenderProfiler.probe(instanceVersionId)

        // Android 9 (Pie) compatibility:
        // GL4ES/opengles2 can fail at buffer mapping on some drivers,
        // causing "Can't map buffer, opengl error 0" then black-screen crash.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            auto == GlRendererKind.GL4ES
        ) {
            auto = GlRendererKind.BOOXIN_GLUES
        }

        // Heavy GL mods on modern MC: prefer BooxinGlues even if version default is GL4ES.
        if (profile.isHeavyGl && auto == GlRendererKind.GL4ES) {
            auto = profile.preferred
        } else if (profile.profileId != "vanilla" &&
            auto != GlRendererKind.GL4ES
        ) {
            auto = profile.preferred
        }

        // vivo/iQOO: user GL4ES on modern MC often hits
        // "Can't map buffer, opengl error 0" (holy gl4es / Adreno).
        // Must use MobileGlues — BOOXIN_GLUES Path A still stages gl4es_114 for vanilla.
        if (OemLaunchProfile.shouldUpgradeGl4esToMobileGlues() &&
            preferred == GlRendererKind.GL4ES &&
            auto != GlRendererKind.GL4ES
        ) {
            android.util.Log.i(
                "RendererBackend",
                "vivo: override GL4ES → MobileGlues for $mcVersionId (${OemLaunchProfile.describe()})"
            )
            return GlRendererKind.MOBILE_GLUES
        }

        if (preferred != null) return preferred
        android.util.Log.i(
            "RendererBackend",
            "auto renderer=${auto.displayName} profile=${profile.profileId} " +
                "mods=${profile.matchedMods.take(6)}"
        )
        return auto
    }

    fun displayName(kind: GlRendererKind): String = RuntimeEnv.libGlString(kind)

    fun booxinRendererToken(kind: GlRendererKind): String = RuntimeEnv.rendererToken(kind)

    fun lastProfile(instanceVersionId: String): ModRenderProfile =
        ModRenderProfiler.probe(instanceVersionId)
}
