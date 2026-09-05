package com.booxin.launcher.core.launch

import com.booxin.launcher.core.java.MinecraftJavaRequirement

/**
 * GLES / desktop-GL translators available to the game process.
 *
 * Built-in: [MOBILE_GLUES], [REL], [MCRENDER], [GL4ES], [BOOXIN_GLUES].
 * Downloadable plugins: [KRYPTON], [LTW], [VULKAN_ZINK], [VIRGL], [FREEDRENO], [ANGLE].
 */
enum class GlRendererKind {
    GL4ES,
    MOBILE_GLUES,
    /** Path A clean-room + MIT/BSD backends (not MobileGlues). */
    BOOXIN_GLUES,
    KRYPTON,
    LTW,
    /** OpenREL — OpenGL 3.3 over GLES (Adreno-focused), bundled as librel.so. */
    REL,
    /** MCrender — GLES translator (libmcrender.so), bundled arm64. */
    MCRENDER,
    VULKAN_ZINK,
    VIRGL,
    FREEDRENO,
    /** Google ANGLE GLES/EGL driver (BSD); used under BooxinGlues/GL4ES. */
    ANGLE;

    val displayName: String
        get() = when (this) {
            GL4ES -> "GL4ES"
            MOBILE_GLUES -> "MobileGlues"
            BOOXIN_GLUES -> "BooxinGlues"
            KRYPTON -> "Krypton Wrapper"
            LTW -> "LTW"
            REL -> "REL"
            MCRENDER -> "MCrender"
            VULKAN_ZINK -> "Vulkan Zink"
            VIRGL -> "VirGL"
            FREEDRENO -> "Freedreno"
            ANGLE -> "ANGLE"
        }

    /** Needs a downloaded plugin package under runtime/renderers/. */
    val requiresPlugin: Boolean
        get() = when (this) {
            GL4ES, MOBILE_GLUES, BOOXIN_GLUES, REL, MCRENDER -> false
            else -> true
        }
}

object GlRendererProfile {
    /**
     * Default renderer for Auto mode.
     * - Pre-1.13 / Beta / Alpha / Classic: holy [GlRendererKind.GL4ES] (MobileGlues usually fails).
     * - Otherwise: [GlRendererKind.MOBILE_GLUES] (best modern default).
     */
    fun forVersion(versionId: String): GlRendererKind =
        if (MinecraftJavaRequirement.needsGl4esRenderer(versionId)) {
            GlRendererKind.GL4ES
        } else {
            GlRendererKind.MOBILE_GLUES
        }
}
