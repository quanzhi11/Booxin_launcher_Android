package com.booxin.launcher.core.launch

import com.booxin.launcher.core.java.MinecraftJavaRequirement

/**
 * GLES / desktop-GL translators available to the game process.
 *
 * Built-in: [GL4ES], [MOBILE_GLUES].
 * Downloadable plugins: [KRYPTON], [LTW], [VULKAN_ZINK], [VIRGL], [FREEDRENO].
 */
enum class GlRendererKind {
    GL4ES,
    MOBILE_GLUES,
    KRYPTON,
    LTW,
    VULKAN_ZINK,
    VIRGL,
    FREEDRENO;

    val displayName: String
        get() = when (this) {
            GL4ES -> "GL4ES"
            MOBILE_GLUES -> "MobileGlues"
            KRYPTON -> "Krypton Wrapper"
            LTW -> "LTW"
            VULKAN_ZINK -> "Vulkan Zink"
            VIRGL -> "VirGL"
            FREEDRENO -> "Freedreno"
        }

    /** Needs a downloaded plugin package under runtime/renderers/. */
    val requiresPlugin: Boolean
        get() = when (this) {
            GL4ES, MOBILE_GLUES -> false
            else -> true
        }
}

object GlRendererProfile {
    fun forVersion(versionId: String): GlRendererKind {
        val parsed = MinecraftJavaRequirement.parseVersion(versionId)
            ?: return GlRendererKind.MOBILE_GLUES
        // Year releases (26.x) and 1.17+ → MobileGlues
        if (parsed.first >= 2) return GlRendererKind.MOBILE_GLUES
        if (parsed.first == 1 && parsed.second >= 17) return GlRendererKind.MOBILE_GLUES
        return GlRendererKind.GL4ES
    }
}
