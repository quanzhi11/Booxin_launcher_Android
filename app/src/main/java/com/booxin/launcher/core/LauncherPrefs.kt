package com.booxin.launcher.core

import android.content.Context
import android.content.SharedPreferences
import com.booxin.launcher.core.launch.GlRendererKind
import kotlin.math.max
import kotlin.math.min

/**
 * Persistent launcher preferences (memory, etc.).
 */
object LauncherPrefs {
    private const val PREFS = "booxin_launcher_prefs"
    private const val KEY_MAX_MEMORY_MB = "max_memory_mb"
    private const val KEY_RENDERER = "renderer_kind"
    private const val KEY_RENDERER_LAST_MANUAL = "renderer_kind_last_manual"
    const val RENDERER_AUTO = "auto"

    /** Soft floor / ceiling for the settings slider (MB). */
    const val MEMORY_MIN_MB = 512
    const val MEMORY_MAX_MB = 8192
    const val MEMORY_STEP_MB = 256
    const val MEMORY_DEFAULT_MB = 2048

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun maxMemoryMb(): Int {
        if (!::prefs.isInitialized) return MEMORY_DEFAULT_MB
        val raw = prefs.getInt(KEY_MAX_MEMORY_MB, MEMORY_DEFAULT_MB)
        return clampMemory(raw)
    }

    fun setMaxMemoryMb(mb: Int) {
        if (!::prefs.isInitialized) return
        prefs.edit().putInt(KEY_MAX_MEMORY_MB, clampMemory(mb)).apply()
    }

    fun clampMemory(mb: Int): Int {
        val stepped = ((mb + MEMORY_STEP_MB / 2) / MEMORY_STEP_MB) * MEMORY_STEP_MB
        return min(MEMORY_MAX_MB, max(MEMORY_MIN_MB, stepped))
    }

    /** Device-aware upper bound so the slider does not offer impossible heaps. */
    fun recommendedMaxMb(): Int {
        val runtimeMax = (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt()
        // Game runs in :game process; allow up to ~70% of a typical phone heap budget,
        // but never above MEMORY_MAX_MB.
        val deviceHint = max(MEMORY_DEFAULT_MB, runtimeMax.coerceAtLeast(1024) * 2)
        return clampMemory(min(MEMORY_MAX_MB, deviceHint))
    }

    /** Preference key: [RENDERER_AUTO] or [GlRendererKind.name]. */
    fun rendererPreference(): String {
        if (!::prefs.isInitialized) return RENDERER_AUTO
        return prefs.getString(KEY_RENDERER, RENDERER_AUTO) ?: RENDERER_AUTO
    }

    fun isRendererAuto(): Boolean = rendererPreference() == RENDERER_AUTO

    /** Null = automatic selection by MC version. */
    fun rendererKind(): GlRendererKind? {
        val raw = rendererPreference()
        if (raw == RENDERER_AUTO) return null
        return runCatching { GlRendererKind.valueOf(raw) }.getOrNull()
    }

    fun setRendererPreference(value: String) {
        if (!::prefs.isInitialized) return
        val edit = prefs.edit().putString(KEY_RENDERER, value)
        if (value != RENDERER_AUTO) {
            edit.putString(KEY_RENDERER_LAST_MANUAL, value)
        }
        edit.apply()
    }

    fun setRendererKind(kind: GlRendererKind?) {
        setRendererPreference(kind?.name ?: RENDERER_AUTO)
    }

    fun setRendererAuto(enabled: Boolean) {
        if (enabled) {
            setRendererPreference(RENDERER_AUTO)
        } else {
            val last = lastManualRendererKind() ?: GlRendererKind.MOBILE_GLUES
            setRendererKind(last)
        }
    }

    fun lastManualRendererKind(): GlRendererKind? {
        if (!::prefs.isInitialized) return null
        val raw = prefs.getString(KEY_RENDERER_LAST_MANUAL, null) ?: return null
        return runCatching { GlRendererKind.valueOf(raw) }.getOrNull()
    }
}
