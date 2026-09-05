package com.booxin.launcher.core.runtime

import android.content.Context
import android.util.Log
import com.booxin.launcher.core.java.MinecraftJavaRequirement
import com.booxin.launcher.core.launch.BooxinLaunchTune
import com.booxin.launcher.core.launch.GlRendererKind
import org.json.JSONObject
import java.io.File

/**
 * Writes MobileGlues (official GLES translator) config.json under MG_DIR_PATH.
 * The backend reads this itself at init — same mechanism other Android launchers use
 * so the translator can apply its own performance paths (ANGLE off, GLSL cache, etc.).
 *
 * JSON key [enableNoError] is logged by MG as `ignoreError`. MobileGlues 1.3.5+ requires
 * ignoreError ≥ 1 (prefer 2) for Minecraft 26.3-snapshot-3 and later.
 */
object MobileGluesConfig {
    private const val TAG = "MobileGluesConfig"
    const val CONFIG_NAME = "config.json"

    fun mgDir(context: Context): File =
        File(context.filesDir, "MG").also { it.mkdirs() }

    /**
     * Ensure a device/tune-aware config exists before JVM start.
     * Does not inject mods; only the translator's own settings file.
     */
    fun ensureForLaunch(
        context: Context,
        kind: GlRendererKind,
        tune: BooxinLaunchTune.Resolved?,
        mcVersionId: String? = null
    ) {
        if (kind != GlRendererKind.MOBILE_GLUES &&
            kind != GlRendererKind.BOOXIN_GLUES
        ) {
            // Path A may stage MobileGlues under max-compat; still write MG config
            // when MG_DIR will be set — caller passes effective gl kind.
            return
        }
        writeProfile(context, tune, mcVersionId)
    }

    /** Always write when MG_DIR_PATH will be used (including max-compat Path A). */
    fun writeProfile(
        context: Context,
        tune: BooxinLaunchTune.Resolved?,
        mcVersionId: String? = null
    ) {
        val dir = mgDir(context)
        val file = File(dir, CONFIG_NAME)
        val tier = tune?.deviceTier ?: BooxinLaunchTune.deviceTier(context)
        val yearBased = mcVersionId?.let { MinecraftJavaRequirement.usesSdlWindowing(it) } == true

        // Values match MobileGlues config.json schema used by the official translator.
        // enableNoError → MG log "ignoreError"; 2 = ignore shader/program/FB errors (needed for 26.3+).
        val json = JSONObject().apply {
            // ANGLE / compute paths often tank FPS on entry-level Mali/Adreno.
            put("enableANGLE", 0)
            put("enableNoError", 2)
            put("enableExtComputeShader", if (yearBased) 1 else 0)
            put("enableExtGL43", if (yearBased || tier == BooxinLaunchTune.DeviceTier.HIGH) 1 else 0)
            put("enableExtTimerQuery", 0)
            put(
                "enableExtDirectStateAccess",
                if (tier == BooxinLaunchTune.DeviceTier.HIGH || yearBased) 1 else 0
            )
            // Default 0 = no shader cache → hitch every world load (PPT stutter).
            put(
                "maxGlslCacheSize",
                when (tier) {
                    BooxinLaunchTune.DeviceTier.LOW -> 48
                    BooxinLaunchTune.DeviceTier.MID -> 96
                    BooxinLaunchTune.DeviceTier.HIGH -> 128
                }
            )
            put("multidrawMode", 0) // Auto
            put("angleDepthClearFixMode", 0)
            put("customGLVersion", if (yearBased) "4.6.0" else 0)
            put("fsr1Setting", if (com.booxin.launcher.core.LauncherPrefs.fsr1Enabled()) 1 else 0)
            put("bufferCoherentAsFlush", 1)
        }

        runCatching {
            file.writeText(json.toString(2))
            Log.i(
                TAG,
                "wrote ${file.absolutePath} tier=$tier yearBased=$yearBased " +
                    "ignoreError=${json.optInt("enableNoError")} " +
                    "gl43=${json.optInt("enableExtGL43")} " +
                    "glslCache=${json.optInt("maxGlslCacheSize")} " +
                    "fsr1=${json.optInt("fsr1Setting")}"
            )
        }.onFailure {
            Log.w(TAG, "config write failed: ${it.message}")
        }
    }
}
