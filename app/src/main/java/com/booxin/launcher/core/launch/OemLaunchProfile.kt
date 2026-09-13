package com.booxin.launcher.core.launch

import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * OEM-specific launch tweaks.
 *
 * vivo/iQOO, Huawei/Honor/HarmonyOS/MagicOS, and OPPO/OnePlus/realme (ColorOS)
 * can fail or hang when JNI_CreateJavaVM is fed a multi-KB -cp /
 * -Djava.class.path blob (Fabric/Knot is the worst case). Holy GL4ES also
 * commonly fails to present frames on these devices.
 *
 * Seen: OPPO Reno7 (PFJM10) vanilla 1.20.1 stalled at CreateJavaVM with
 * 40-jar -cp and a generic OEM profile (no classpath.jar).
 */
object OemLaunchProfile {

    /** vivo / iQOO family (OriginOS). */
    fun isVivoFamily(): Boolean {
        val hay = deviceHaystack()
        return "vivo" in hay || "iqoo" in hay
    }

    /**
     * Huawei / Honor tablets & phones, including HarmonyOS and MagicOS.
     * Manufacturer may be HUAWEI / HONOR / HiHonor; DISPLAY often contains
     * HarmonyOS / MagicOS (not always "honor" in MODEL).
     */
    fun isHuaweiFamily(): Boolean {
        val hay = deviceHaystack()
        if ("huawei" in hay || "honor" in hay || "hihonor" in hay) return true
        val display = Build.DISPLAY.orEmpty().lowercase(Locale.US)
        if ("harmonyos" in display || "harmony" in display) return true
        if ("magicos" in display || "magic os" in display || "magicui" in display) return true
        // Some MagicOS builds only stamp FINGERPRINT / HOST.
        val finger = Build.FINGERPRINT.orEmpty().lowercase(Locale.US)
        return "huawei" in finger || "honor" in finger || "hihonor" in finger ||
            "magicos" in finger || "harmony" in finger
    }

    /**
     * OPPO / OnePlus / realme (ColorOS / OxygenOS / realme UI).
     * Same CreateJavaVM long-classpath hang as vivo; ColorOS also often
     * hides native logcat so the UI only shows “正在创建 JVM”.
     */
    fun isOplusFamily(): Boolean {
        val hay = deviceHaystack()
        if ("oppo" in hay || "oneplus" in hay || "realme" in hay || "oplus" in hay) {
            return true
        }
        val display = Build.DISPLAY.orEmpty().lowercase(Locale.US)
        if ("coloros" in display || "oxygenos" in display || "realmeui" in display) {
            return true
        }
        val finger = Build.FINGERPRINT.orEmpty().lowercase(Locale.US)
        return "coloros" in finger || "oxygenos" in finger || "realmeui" in finger
    }

    /** OEMs that need CreateJavaVM classpath shrink. */
    fun needsClasspathMitigation(): Boolean =
        isVivoFamily() || isHuaweiFamily() || isOplusFamily()

    /**
     * Forge BootstrapLauncher: short -cp (bootstrap only) + legacyClassPath.file.
     * Other OEMs keep the full -cp.
     */
    fun needsForgeShortClasspath(): Boolean = needsClasspathMitigation()

    /**
     * Vanilla / OptiFine: collapse -cp into one classpath.jar so
     * JNI_CreateJavaVM does not parse a multi-KB classpath string.
     *
     * Never use this for Fabric/Quilt Knot — Manifest Class-Path pollutes
     * AppClassLoader with fabric-loader and crashes Knot.
     */
    fun needsClasspathJar(): Boolean = needsClasspathMitigation()

    /**
     * On vivo/iQOO, Huawei/Honor and ColorOS, holy GL4ES / BooxinGlues Path A
     * commonly fails (map buffer / no TextureView frames / Mojang black screen).
     * Auto and user picks of those kinds are remapped to MobileGlues.
     * REL / MCrender / plugins are never swapped.
     */
    fun shouldUpgradeGl4esToMobileGlues(): Boolean = needsClasspathMitigation()

    /** True when [kind] would stage holy GL4ES and this OEM must avoid it. */
    fun shouldForceMobileGlues(kind: GlRendererKind): Boolean =
        shouldUpgradeGl4esToMobileGlues() &&
            (kind == GlRendererKind.GL4ES || kind == GlRendererKind.BOOXIN_GLUES)

    /**
     * OriginOS often ignores [android.view.TextureView.setTransform], so wallpaper
     * video stays native-sized in the corner. Use View scaleX/scaleY instead.
     */
    fun needsTextureViewScaleHack(): Boolean = isVivoFamily()

    /**
     * Honor/Huawei/ColorOS TextureView + holy GL often needs longer
     * force-rebind grace and more attempts before declaring no frames.
     */
    fun needsPatientForceRebind(): Boolean =
        isHuaweiFamily() || isVivoFamily() || isOplusFamily()

    /**
     * ColorOS often thermal-throttles and deprioritizes `:game` under QUALITY/high RD.
     * AUTO should pick a more conservative preset than RAM alone suggests.
     */
    fun needsConservativeAutoTune(): Boolean = isOplusFamily()

    /**
     * Raise scheduling priority / hint OEM game boost for the current process.
     * Call from `:game` (LaunchActivity / GameLaunchService) on ColorOS family.
     */
    fun applyGameProcessBoost(context: Context) {
        if (!isOplusFamily()) return
        runCatching {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
            )
        }
        // Android 13+: tell the system we are in active gameplay (helps Game Mode).
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                val gm = context.getSystemService(android.app.GameManager::class.java)
                gm?.setGameState(
                    android.app.GameState(
                        /* isLoading = */ false,
                        android.app.GameState.MODE_GAMEPLAY_INTERRUPTIBLE
                    )
                )
            }
        }
        // Best-effort ColorOS / OxygenOS game-boost broadcasts (no-op if absent).
        val pkg = context.packageName
        listOf(
            "oplus.intent.action.GAME_BOOST",
            "com.oplus.games.action.GAME_BOOST",
            "com.oneplus.gamespace.action.ADD_GAME"
        ).forEach { action ->
            runCatching {
                context.sendBroadcast(
                    android.content.Intent(action).putExtra("package_name", pkg)
                )
            }
        }
        android.util.Log.i("OemLaunchProfile", "oplus game process boost applied (${describe()})")
    }

    fun describe(): String {
        val tags = buildList {
            if (isVivoFamily()) add("vivo")
            if (isHuaweiFamily()) add("huawei")
            if (isOplusFamily()) add("oplus")
        }.joinToString("+").ifBlank { "generic" }
        return "$tags ${Build.MANUFACTURER}/${Build.BRAND} ${Build.MODEL}"
    }

    private fun deviceHaystack(): String =
        listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.PRODUCT,
            Build.DEVICE,
            Build.MODEL,
            Build.HARDWARE,
            Build.DISPLAY,
            Build.FINGERPRINT
        ).joinToString(" ").lowercase(Locale.US)
}
