package com.booxin.launcher.core.launch

import com.booxin.launcher.core.java.MinecraftJavaRequirement

/**
 * Picks GLES translator by Minecraft version.
 *
 * ≤1.16.x still uses fixed-function calls like [glFogfv]; MobileGlues often
 * returns null there → crash while rendering the title screen. Holy gl4es
 * handles that era. 1.17+ prefers MobileGlues.
 */
enum class GlRendererKind {
    GL4ES,
    MOBILE_GLUES
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
