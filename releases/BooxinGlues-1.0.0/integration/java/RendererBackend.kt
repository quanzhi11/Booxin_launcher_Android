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
        // ADB/QA: write e.g. "REL" to external files/force_renderer.txt (optional).
        forceRendererSidecar()?.let { return it }
        // 2026-09：26.3+ 暂定一律 BooxinGlues（偏好里的 MG/Auto 也覆盖）。
        if (com.booxin.launcher.core.java.MinecraftJavaRequirement.usesSdlWindowing(mcVersionId)) {
            android.util.Log.i(
                "RendererBackend",
                "26.3+ → BooxinGlues for $mcVersionId"
            )
            return GlRendererKind.BOOXIN_GLUES
        }
        val legacyNeedsGl4es =
            com.booxin.launcher.core.java.MinecraftJavaRequirement.needsGl4esRenderer(mcVersionId)

        // Legacy gate may temporarily pin GL4ES; unpin before resolving modern launches.
        if (!legacyNeedsGl4es && LauncherPrefs.restoreRendererAfterLegacyIfNeeded()) {
            android.util.Log.i(
                "RendererBackend",
                "restored renderer after legacy GL4ES pin → ${LauncherPrefs.rendererPreference()}"
            )
        }

        val preferred = LauncherPrefs.rendererKind()
        var auto = GlRendererProfile.forVersion(mcVersionId)
        val profile = ModRenderProfiler.probe(instanceVersionId)

        // Android 9 (Pie): avoid holy GL4ES buffer-map crashes → MobileGlues,
        // except ancient clients that only start on GL4ES.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            auto == GlRendererKind.GL4ES &&
            !legacyNeedsGl4es
        ) {
            auto = GlRendererKind.MOBILE_GLUES
        }

        // Heavy / non-vanilla packs: keep profile preferred — but never demote
        // ancient clients off GL4ES (MobileGlues usually cannot boot them).
        if (!legacyNeedsGl4es) {
            if (profile.isHeavyGl && auto == GlRendererKind.GL4ES) {
                auto = profile.preferred
            } else if (profile.profileId != "vanilla" &&
                auto != GlRendererKind.GL4ES
            ) {
                auto = profile.preferred
            }
        } else {
            auto = GlRendererKind.GL4ES
        }

        // Explicit user pick — except OEM cannot run holy GL4ES / Path A
        // (ancient still keeps user pick; OEM remap would brick Beta/Alpha).
        if (preferred != null) {
            if (!legacyNeedsGl4es && OemLaunchProfile.shouldForceMobileGlues(preferred)) {
                android.util.Log.i(
                    "RendererBackend",
                    "OEM: user ${preferred.displayName} → MobileGlues " +
                        "(${OemLaunchProfile.describe()})"
                )
                return GlRendererKind.MOBILE_GLUES
            }
            android.util.Log.i(
                "RendererBackend",
                "user renderer=${preferred.displayName} for $mcVersionId " +
                    "(${OemLaunchProfile.describe()})"
            )
            return preferred
        }

        // Auto-only: vivo/iQOO / Huawei/Honor / ColorOS holy GL4ES often black-screens
        // on modern MC — do not remap ancient (must stay on GL4ES).
        if (!legacyNeedsGl4es && OemLaunchProfile.shouldForceMobileGlues(auto)) {
            android.util.Log.i(
                "RendererBackend",
                "OEM auto: ${auto.displayName} → MobileGlues for $mcVersionId " +
                    "(${OemLaunchProfile.describe()})"
            )
            return GlRendererKind.MOBILE_GLUES
        }

        android.util.Log.i(
            "RendererBackend",
            "auto renderer=${auto.displayName} profile=${profile.profileId} " +
                "mods=${profile.matchedMods.take(6)} legacyGl4es=$legacyNeedsGl4es"
        )
        return auto
    }

    fun displayName(kind: GlRendererKind): String = RuntimeEnv.libGlString(kind)

    fun booxinRendererToken(kind: GlRendererKind): String = RuntimeEnv.rendererToken(kind)

    fun lastProfile(instanceVersionId: String): ModRenderProfile =
        ModRenderProfiler.probe(instanceVersionId)

    private fun forceRendererSidecar(): GlRendererKind? {
        val ctx = runCatching { com.booxin.launcher.BooxinApp.getAppContext() }.getOrNull()
            ?: return null
        val file = java.io.File(ctx.getExternalFilesDir(null), "force_renderer.txt")
        if (!file.isFile) return null
        val name = runCatching { file.readText().trim() }.getOrNull().orEmpty()
        if (name.isBlank()) return null
        return runCatching { GlRendererKind.valueOf(name) }.getOrNull()?.also {
            android.util.Log.i("RendererBackend", "force_renderer.txt → ${it.displayName}")
        }
    }
}
