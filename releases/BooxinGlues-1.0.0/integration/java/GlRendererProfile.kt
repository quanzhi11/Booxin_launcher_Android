package com.booxin.launcher.core.launch

import com.booxin.launcher.core.java.MinecraftJavaRequirement

/**
 * GLES / desktop-GL translators available to the game process.
 *
 * Built-in: [MOBILE_GLUES], [REL], [MCRENDER], [GL4ES], [BOOXIN_GLUES].
 * Downloadable plugins: [BOOXIN_ZINK], [KRYPTON], [LTW], [VULKAN_ZINK], [VIRGL], [FREEDRENO], [ANGLE].
 */
enum class GlRendererKind {
    GL4ES,
    MOBILE_GLUES,
    /**
     * BooxinGlues：内置 Mesa OSMesa/Zink（MIT，libOSMesa_25.so）。
     * 26.3+ SDL 窗口默认走这条；更早版本仍用 MobileGlues / REL 等。
     */
    BOOXIN_GLUES,
    /**
     * @deprecated 旧偏好名，等同 [BOOXIN_GLUES]。
     */
    @Deprecated("Use BOOXIN_GLUES")
    BOOXIN_ZINK,
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
            BOOXIN_ZINK -> "BooxinGlues"
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
            GL4ES, MOBILE_GLUES, REL, MCRENDER, BOOXIN_GLUES, BOOXIN_ZINK -> false
            else -> true
        }
}

object GlRendererProfile {
    /** Auto：远古 GL4ES；26.3+ BooxinGlues；其余 MobileGlues。 */
    fun forVersion(versionId: String): GlRendererKind = when {
        MinecraftJavaRequirement.needsGl4esRenderer(versionId) -> GlRendererKind.GL4ES
        MinecraftJavaRequirement.usesSdlWindowing(versionId) -> GlRendererKind.BOOXIN_GLUES
        else -> GlRendererKind.MOBILE_GLUES
    }
}
