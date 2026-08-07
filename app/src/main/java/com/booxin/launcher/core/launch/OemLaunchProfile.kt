package com.booxin.launcher.core.launch

import android.os.Build
import java.util.Locale

/**
 * OEM-specific launch tweaks. Keep the common path lean; only slow-CreateJavaVM
 * devices (vivo family) use Forge short -cp + legacyClassPath.file.
 */
object OemLaunchProfile {

    /** vivo / iQOO often stall for minutes inside JNI_CreateJavaVM with a huge -cp. */
    fun needsForgeShortClasspath(): Boolean {
        val hay = listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.PRODUCT,
            Build.DEVICE,
            Build.MODEL
        ).joinToString(" ").lowercase(Locale.US)
        return "vivo" in hay || "iqoo" in hay
    }

    fun describe(): String =
        "${Build.MANUFACTURER}/${Build.BRAND} ${Build.MODEL}"
}
