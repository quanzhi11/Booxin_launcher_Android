package com.booxin.launcher.core.runtime

import android.content.Context
import android.util.Log
import com.booxin.launcher.core.java.MinecraftJavaRequirement
import com.booxin.launcher.core.launch.BooxinLaunchTune
import com.booxin.launcher.core.launch.GlRendererKind
import com.booxin.launcher.core.launch.OemLaunchProfile
import org.json.JSONObject
import java.io.File

/**
 * Writes MobileGlues (official GLES translator) config.json under MG_DIR_PATH.
 *
 * JSON key [enableNoError] is logged by MG as `ignoreError`. MobileGlues 1.3.5+
 * requires ignore-shader errors for Minecraft 26.3-snapshot-3+.
 *
 * Year-based (26.3+) notes:
 * - customGLVersion must be a number (0 = MG default). String "4.6.0" is ignored.
 * - Forcing 4.6 on MG 2.0 made terrain shaders hard-fail; keep 0 and disable DSA
 *   (DSA rewrite was producing `_uniform` / `_instance` GLSL errors).
 */
object MobileGluesConfig {
    private const val TAG = "MobileGluesConfig"
    const val CONFIG_NAME = "config.json"

    fun mgDir(context: Context): File =
        File(context.filesDir, "MG").also { it.mkdirs() }

    fun ensureForLaunch(
        context: Context,
        kind: GlRendererKind,
        tune: BooxinLaunchTune.Resolved?,
        mcVersionId: String? = null
    ) {
        if (kind != GlRendererKind.MOBILE_GLUES &&
            kind != GlRendererKind.BOOXIN_GLUES
        ) {
            return
        }
        writeProfile(context, tune, mcVersionId)
    }

    fun writeProfile(
        context: Context,
        tune: BooxinLaunchTune.Resolved?,
        mcVersionId: String? = null
    ) {
        val dir = mgDir(context)
        val file = File(dir, CONFIG_NAME)
        val tier = tune?.deviceTier ?: BooxinLaunchTune.deviceTier(context)
        val yearBased = mcVersionId?.let { MinecraftJavaRequirement.usesSdlWindowing(it) } == true
        // ColorOS throttles hard; DSA + large GLSL cache add CPU/GPU spikes under MG.
        val oplus = OemLaunchProfile.isOplusFamily()

        val json = JSONObject().apply {
            put("enableANGLE", 0)
            // 2 = full ignore shader/program/FB errors (needed so 26.3 does not hard-crash).
            put("enableNoError", 2)
            put("enableExtComputeShader", if (yearBased) 1 else 0)
            put("enableExtTimerQuery", 0)
            // DSA on + 26.3 terrain instance attrs → MG `_uniform` rewrite crash.
            put(
                "enableExtDirectStateAccess",
                when {
                    yearBased || oplus -> 0
                    tier == BooxinLaunchTune.DeviceTier.HIGH -> 1
                    else -> 0
                }
            )
            put(
                "maxGlslCacheSize",
                when {
                    oplus -> when (tier) {
                        BooxinLaunchTune.DeviceTier.LOW -> 32
                        BooxinLaunchTune.DeviceTier.MID -> 64
                        BooxinLaunchTune.DeviceTier.HIGH -> 96
                    }
                    else -> when (tier) {
                        BooxinLaunchTune.DeviceTier.LOW -> 48
                        BooxinLaunchTune.DeviceTier.MID -> 96
                        BooxinLaunchTune.DeviceTier.HIGH -> 128
                    }
                }
            )
            put("multidrawMode", 0)
            put("angleDepthClearFixMode", 0)
            // 0 = MG default (logs as 4.0.0). Year-based 26.3 terrain uses instance
            // attrs; advertising 4.x triggers a broken `_uniform`/`_instance` rewrite.
            // Keep 0 + DSA off; clear glsl_cache so old cheat artifacts are not reused.
            put("customGLVersion", 0)
            put("fsr1Setting", if (com.booxin.launcher.core.LauncherPrefs.fsr1Enabled()) 1 else 0)
            put("bufferCoherentAsFlush", 1)
            put("hideMGEnvLevel", 0)
        }

        // Always wipe GLSL cache for year-based — stale cheats paint black menus/worlds.
        if (yearBased) {
            clearGlslCache(dir)
        }

        runCatching {
            file.writeText(json.toString(2))
            Log.i(
                TAG,
                "wrote ${file.absolutePath} tier=$tier yearBased=$yearBased oplus=$oplus " +
                    "ignoreError=${json.optInt("enableNoError")} " +
                    "customGL=${json.opt("customGLVersion")} " +
                    "dsa=${json.optInt("enableExtDirectStateAccess")} " +
                    "compute=${json.optInt("enableExtComputeShader")} " +
                    "glslCache=${json.optInt("maxGlslCacheSize")}"
            )
        }.onFailure {
            Log.w(TAG, "config write failed: ${it.message}")
        }
    }

    private fun clearGlslCache(mgDir: File) {
        val cache = File(mgDir, "glsl_cache")
        if (!cache.isDirectory) return
        var n = 0
        cache.walkBottomUp().forEach { f ->
            if (f != cache && f.delete()) n++
        }
        Log.i(TAG, "cleared glsl_cache entries=$n under ${cache.absolutePath}")
    }
}
