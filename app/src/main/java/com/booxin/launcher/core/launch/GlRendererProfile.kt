package com.booxin.launcher.core.launch

import com.booxin.launcher.core.java.MinecraftJavaRequirement

/**
 * GLES / desktop-GL translators available to the game process.
 *
 * Built-in: [GL4ES], [MOBILE_GLUES], [BOOXIN_GLUES].
 * Downloadable plugins: [KRYPTON], [LTW], [VULKAN_ZINK], [VIRGL], [FREEDRENO], [ANGLE].
 */
enum class GlRendererKind {
    GL4ES,
    MOBILE_GLUES,
    /** Path A clean-room + MIT/BSD backends (not MobileGlues). */
    BOOXIN_GLUES,
    KRYPTON,
    LTW,
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
            VULKAN_ZINK -> "Vulkan Zink"
            VIRGL -> "VirGL"
            FREEDRENO -> "Freedreno"
            ANGLE -> "ANGLE"
        }

    /** Needs a downloaded plugin package under runtime/renderers/. */
    val requiresPlugin: Boolean
        get() = when (this) {
            GL4ES, MOBILE_GLUES, BOOXIN_GLUES -> false
            else -> true
        }
}

object GlRendererProfile {
    fun forVersion(versionId: String): GlRendererKind {
        val parsed = MinecraftJavaRequirement.parseVersion(versionId)
            ?: return GlRendererKind.BOOXIN_GLUES
        // Year releases (26.x) and 1.17+ → BooxinGlues (Path A)
        if (parsed.first >= 2) return GlRendererKind.BOOXIN_GLUES
        if (parsed.first == 1 && parsed.second >= 17) return GlRendererKind.BOOXIN_GLUES
        return GlRendererKind.GL4ES
    }
}
