package com.booxin.launcher.core.launch

import android.os.Build
import java.util.Locale

/**
 * OEM-specific launch tweaks. Keep the common path lean; only vivo/iQOO need
 * CreateJavaVM workarounds (huge -cp / option blobs stall for minutes).
 */
object OemLaunchProfile {

    /** vivo / iQOO family (OriginOS). */
    fun isVivoFamily(): Boolean {
        val hay = listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.PRODUCT,
            Build.DEVICE,
            Build.MODEL
        ).joinToString(" ").lowercase(Locale.US)
        return "vivo" in hay || "iqoo" in hay
    }

    /**
     * Forge BootstrapLauncher: short -cp (bootstrap only) + legacyClassPath.file.
     * Other OEMs keep the full -cp.
     */
    fun needsForgeShortClasspath(): Boolean = isVivoFamily()

    /**
     * Vanilla / OptiFine / Knot: collapse -cp into one classpath.jar so
     * JNI_CreateJavaVM does not parse a multi-KB classpath string (V2444A hang).
     */
    fun needsClasspathJar(): Boolean = isVivoFamily()

    /**
     * On vivo/iQOO, user-selected GL4ES (or BooxinGlues→holy gl4es) for modern MC
     * commonly fails with "Can't map buffer, opengl error 0". Force MobileGlues.
     */
    fun shouldUpgradeGl4esToMobileGlues(): Boolean = isVivoFamily()

    /**
     * OriginOS often ignores [android.view.TextureView.setTransform], so wallpaper
     * video stays native-sized in the corner. Use View scaleX/scaleY instead.
     */
    fun needsTextureViewScaleHack(): Boolean = isVivoFamily()

    fun describe(): String =
        "${Build.MANUFACTURER}/${Build.BRAND} ${Build.MODEL}"
}
