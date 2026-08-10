package com.booxin.launcher.core.runtime

import com.booxin.launcher.core.LauncherPrefs
import com.booxin.launcher.core.launch.GlRendererKind
import com.booxin.launcher.core.launch.OemLaunchProfile
import java.io.File

/**
 * Path A / max-compat backend resolution for [GlRendererKind.BOOXIN_GLUES].
 */
enum class BooxinGlEngine {
    CLEANROOM,
    ZINK,
    ANGLE_EGL,
    GL4ES_MIT,
    /** Max-compat only: stage MobileGlues (LGPL) under BooxinGlues product name. */
    MOBILE_GLUES_COMPAT
}

data class BooxinGlResolved(
    val engine: BooxinGlEngine,
    val stageAs: GlRendererKind,
    val backendEnv: String,
    val note: String
)

object BooxinGlStack {
    private fun pluginReady(kind: GlRendererKind): Boolean =
        runCatching { RendererInstaller.isInstalled(kind) }.getOrDefault(false)

    fun resolve(
        stagedNatives: File,
        profile: ModRenderProfile?
    ): BooxinGlResolved {
        val cleanroom = File(stagedNatives, "libbooxingl.so")
        val heavy = profile?.isHeavyGl == true
        val shaders = profile?.features?.contains("shaders") == true
        val maxCompat = runCatching { LauncherPrefs.isMaxGlCompat() }.getOrDefault(true)
        val mg = File(stagedNatives, "libmobileglues.so")
        val angleReady = pluginReady(GlRendererKind.ANGLE) ||
            File(stagedNatives, "libEGL_angle.so").isFile

        // Prefer shipped clean-room façade whenever present (M7+).
        if (cleanroom.isFile) {
            val useAngle = angleReady && (shaders || heavy)
            return BooxinGlResolved(
                engine = if (useAngle) BooxinGlEngine.ANGLE_EGL else BooxinGlEngine.CLEANROOM,
                stageAs = GlRendererKind.BOOXIN_GLUES,
                backendEnv = if (useAngle) "angle" else "gl4es",
                note = if (useAngle) {
                    "Path A: libbooxingl.so + ANGLE EGL"
                } else {
                    "Path A: libbooxingl.so + GL4ES holy"
                }
            )
        }

        if (heavy && pluginReady(GlRendererKind.VULKAN_ZINK)) {
            return BooxinGlResolved(
                engine = BooxinGlEngine.ZINK,
                stageAs = GlRendererKind.VULKAN_ZINK,
                backendEnv = "zink",
                note = "最大兼容/Path A: Mesa Zink (MIT) profile=${profile?.profileId}"
            )
        }

        if (maxCompat && (shaders || heavy) && mg.isFile) {
            return BooxinGlResolved(
                engine = BooxinGlEngine.MOBILE_GLUES_COMPAT,
                stageAs = GlRendererKind.MOBILE_GLUES,
                backendEnv = "mobileglues",
                note = "最大兼容: MobileGlues (LGPL) 覆盖光影/重模组（无 clean-room .so）"
            )
        }

        // vivo/iQOO Adreno: holy GL4ES often throws Can't map buffer on 1.17+/26.x
        // even for vanilla — prefer MobileGlues before Path A gl4es fallback.
        if (OemLaunchProfile.isVivoFamily() && maxCompat && mg.isFile) {
            return BooxinGlResolved(
                engine = BooxinGlEngine.MOBILE_GLUES_COMPAT,
                stageAs = GlRendererKind.MOBILE_GLUES,
                backendEnv = "mobileglues",
                note = "vivo: MobileGlues（避免 GL4ES Can't map buffer）"
            )
        }

        if (angleReady && (shaders || heavy)) {
            return BooxinGlResolved(
                engine = BooxinGlEngine.ANGLE_EGL,
                stageAs = GlRendererKind.BOOXIN_GLUES,
                backendEnv = "angle",
                note = "Path A: GL4ES + ANGLE EGL"
            )
        }

        return BooxinGlResolved(
            engine = BooxinGlEngine.GL4ES_MIT,
            stageAs = GlRendererKind.BOOXIN_GLUES,
            backendEnv = "gl4es",
            note = "Path A: GL4ES (MIT)"
        )
    }

    fun fallbackChain(profile: ModRenderProfile?): List<GlRendererKind> {
        val maxCompat = runCatching { LauncherPrefs.isMaxGlCompat() }.getOrDefault(true)
        val chain = mutableListOf(
            GlRendererKind.BOOXIN_GLUES,
            GlRendererKind.VULKAN_ZINK,
            GlRendererKind.ANGLE,
            GlRendererKind.LTW,
            GlRendererKind.GL4ES
        )
        if (maxCompat) {
            chain += GlRendererKind.MOBILE_GLUES
        }
        profile?.fallback?.forEach { if (it !in chain) chain += it }
        if (maxCompat && GlRendererKind.MOBILE_GLUES !in chain) {
            chain += GlRendererKind.MOBILE_GLUES
        }
        return chain
    }

    @Deprecated("Use fallbackChain", ReplaceWith("fallbackChain(profile)"))
    fun mitFallbackChain(profile: ModRenderProfile?): List<GlRendererKind> = fallbackChain(profile)
}
