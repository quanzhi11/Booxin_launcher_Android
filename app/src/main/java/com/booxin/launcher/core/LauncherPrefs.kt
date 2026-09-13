package com.booxin.launcher.core

import android.content.Context
import android.content.SharedPreferences
import com.booxin.launcher.core.launch.BooxinLaunchTune
import com.booxin.launcher.core.launch.GlRendererKind
import com.booxin.launcher.core.runtime.BooxinGlCompatMode
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
    private const val KEY_RENDERER_MIGRATED_V539 = "renderer_migrated_mobileglues_v539"
    private const val KEY_RENDERER_LEGACY_TEMP = "renderer_legacy_temp_gl4es"
    private const val KEY_RENDERER_BEFORE_LEGACY = "renderer_before_legacy_gl4es"
    private const val KEY_RENDERER_LEGACY_PIN_HEALED = "renderer_legacy_gl4es_pin_healed_v1"
    private const val KEY_GUI_SCALE_AUTO_RESTORED = "options_gui_scale_auto_restored_v1"
    private const val KEY_GL_COMPAT_MODE = "booxin_gl_compat_mode"
    private const val KEY_GAME_DIR_LOCATION = "game_dir_location"
    private const val KEY_GAME_DIR_CUSTOM = "game_dir_custom"
    private const val KEY_LAUNCH_TUNE_MODE = "launch_tune_mode"
    private const val KEY_RENDER_DISTANCE = "render_distance"
    private const val KEY_VSYNC = "enable_vsync"
    private const val KEY_FSR1 = "fsr1_enabled"
    private const val KEY_FANCY_GRAPHICS = "fancy_graphics"
    private const val KEY_MASTER_VOLUME = "master_volume_percent"
    private const val KEY_BACKGROUND_THEME = "background_theme"
    private const val KEY_BG_ALIGN_MODE = "background_align_mode"
    private const val KEY_BG_ALIGN_SCALE_X = "background_align_scale_x"
    private const val KEY_BG_ALIGN_SCALE_Y = "background_align_scale_y"
    private const val KEY_BG_ALIGN_OFFSET_X = "background_align_offset_x"
    private const val KEY_BG_ALIGN_OFFSET_Y = "background_align_offset_y"
    private const val KEY_AI_PENDING_OUT_TRADE_NO = "ai_pending_out_trade_no"
    private const val KEY_AI_PENDING_USER_KEY = "ai_pending_user_key"
    private const val KEY_USER_AGREEMENT_ACCEPTED = "user_agreement_accepted_v1"
    private const val KEY_CONTROLLER_NAV = "controller_nav_enabled"
    const val RENDERER_AUTO = "auto"

    /** Soft floor / ceiling for the settings slider (MB). */
    const val MEMORY_MIN_MB = 512
    const val MEMORY_MAX_MB = 8192
    const val MEMORY_STEP_MB = 256
    const val MEMORY_DEFAULT_MB = 2048

    const val RENDER_DISTANCE_MIN = 2
    const val RENDER_DISTANCE_MAX = 32
    const val RENDER_DISTANCE_DEFAULT = 8
    const val MASTER_VOLUME_DEFAULT = 100

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // One-time: old default was Auto→BooxinGlues/GL4ES (often fails to start).
        // Prefer MobileGlues (manual list #1) unless user already picked another renderer.
        if (!prefs.contains(KEY_RENDERER_MIGRATED_V539)) {
            val raw = prefs.getString(KEY_RENDERER, null)
            if (raw == null || raw == RENDERER_AUTO || raw == GlRendererKind.BOOXIN_GLUES.name) {
                prefs.edit()
                    .putString(KEY_RENDERER, GlRendererKind.MOBILE_GLUES.name)
                    .putString(KEY_RENDERER_LAST_MANUAL, GlRendererKind.MOBILE_GLUES.name)
                    .putBoolean(KEY_RENDERER_MIGRATED_V539, true)
                    .apply()
            } else {
                prefs.edit().putBoolean(KEY_RENDERER_MIGRATED_V539, true).apply()
            }
        }
        // Older builds permanently pinned GL4ES after launching ancient clients.
        // Heal once so modern versions return to MobileGlues.
        if (!prefs.getBoolean(KEY_RENDERER_LEGACY_PIN_HEALED, false)) {
            val raw = prefs.getString(KEY_RENDERER, null)
            val edit = prefs.edit().putBoolean(KEY_RENDERER_LEGACY_PIN_HEALED, true)
            if (raw == GlRendererKind.GL4ES.name ||
                prefs.getBoolean(KEY_RENDERER_LEGACY_TEMP, false)
            ) {
                val restore = prefs.getString(KEY_RENDERER_BEFORE_LEGACY, null)
                    ?.takeUnless { it == GlRendererKind.GL4ES.name || it == RENDERER_AUTO }
                    ?: GlRendererKind.MOBILE_GLUES.name
                edit.putString(KEY_RENDERER, restore)
                    .putString(KEY_RENDERER_LAST_MANUAL, restore)
                    .putBoolean(KEY_RENDERER_LEGACY_TEMP, false)
                    .remove(KEY_RENDERER_BEFORE_LEGACY)
            }
            edit.apply()
        }
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

    fun launchTuneMode(): BooxinLaunchTune.Mode {
        if (!::prefs.isInitialized) return BooxinLaunchTune.Mode.AUTO
        return BooxinLaunchTune.Mode.fromPref(prefs.getString(KEY_LAUNCH_TUNE_MODE, null))
    }

    fun setLaunchTuneMode(mode: BooxinLaunchTune.Mode) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_LAUNCH_TUNE_MODE, mode.prefValue).apply()
    }

    /** Mark prefs as user-customized when individual knobs change under a preset. */
    fun markLaunchTuneCustomIfPreset() {
        if (!::prefs.isInitialized) return
        val current = launchTuneMode()
        if (current != BooxinLaunchTune.Mode.CUSTOM && current != BooxinLaunchTune.Mode.AUTO) {
            setLaunchTuneMode(BooxinLaunchTune.Mode.CUSTOM)
        }
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

    /** Preference key: [RENDERER_AUTO] or [GlRendererKind.name]. Default: MobileGlues. */
    fun rendererPreference(): String {
        if (!::prefs.isInitialized) return GlRendererKind.MOBILE_GLUES.name
        return prefs.getString(KEY_RENDERER, GlRendererKind.MOBILE_GLUES.name)
            ?: GlRendererKind.MOBILE_GLUES.name
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
        // A real settings/user pick cancels any pending legacy temp restore.
        edit.putBoolean(KEY_RENDERER_LEGACY_TEMP, false)
            .remove(KEY_RENDERER_BEFORE_LEGACY)
        edit.apply()
    }

    fun setRendererKind(kind: GlRendererKind?) {
        setRendererPreference(kind?.name ?: RENDERER_AUTO)
    }

    /**
     * Temporary pin to holy GL4ES for ancient clients only.
     * Does not overwrite [KEY_RENDERER_LAST_MANUAL] as a permanent user choice;
     * [restoreRendererAfterLegacyIfNeeded] puts the previous preference back.
     */
    fun applyLegacyGl4esTemporarily() {
        if (!::prefs.isInitialized) return
        val edit = prefs.edit()
        if (!prefs.getBoolean(KEY_RENDERER_LEGACY_TEMP, false)) {
            val before = rendererPreference()
            edit.putString(KEY_RENDERER_BEFORE_LEGACY, before)
        }
        edit.putString(KEY_RENDERER, GlRendererKind.GL4ES.name)
            .putBoolean(KEY_RENDERER_LEGACY_TEMP, true)
            .apply()
    }

    /**
     * After a legacy GL4ES pin, restore MobileGlues (or the prior preference)
     * when launching a modern version. Returns true if prefs changed.
     */
    fun restoreRendererAfterLegacyIfNeeded(): Boolean {
        if (!::prefs.isInitialized) return false
        if (!prefs.getBoolean(KEY_RENDERER_LEGACY_TEMP, false)) return false
        val restore = prefs.getString(KEY_RENDERER_BEFORE_LEGACY, null)
            ?.takeUnless { it == GlRendererKind.GL4ES.name }
            ?: GlRendererKind.MOBILE_GLUES.name
        prefs.edit()
            .putString(KEY_RENDERER, restore)
            .putBoolean(KEY_RENDERER_LEGACY_TEMP, false)
            .remove(KEY_RENDERER_BEFORE_LEGACY)
            .apply()
        return true
    }

    fun isLegacyGl4esTemp(): Boolean =
        ::prefs.isInitialized && prefs.getBoolean(KEY_RENDERER_LEGACY_TEMP, false)

    /**
     * One-shot: a prior build forced guiScale=3 into options.txt (menus look tiny
     * on high-res phones vs Auto). Returns true the first time so the patch can
     * write guiScale:0; later launches leave the player's in-game choice alone.
     */
    fun consumeRestoreGuiScaleToAuto(): Boolean {
        if (!::prefs.isInitialized) return false
        if (prefs.getBoolean(KEY_GUI_SCALE_AUTO_RESTORED, false)) return false
        prefs.edit().putBoolean(KEY_GUI_SCALE_AUTO_RESTORED, true).apply()
        return true
    }

    fun setRendererAuto(enabled: Boolean) {
        if (enabled) {
            setRendererPreference(RENDERER_AUTO)
        } else {
            val last = lastManualRendererKind()
                ?.takeUnless { it == GlRendererKind.BOOXIN_GLUES }
                ?: GlRendererKind.MOBILE_GLUES
            setRendererKind(last)
        }
    }

    fun lastManualRendererKind(): GlRendererKind? {
        if (!::prefs.isInitialized) return null
        val raw = prefs.getString(KEY_RENDERER_LAST_MANUAL, null) ?: return null
        return runCatching { GlRendererKind.valueOf(raw) }.getOrNull()
    }

    /**
     * Default [BooxinGlCompatMode.MAX_COMPAT] so Auto/BooxinGlues can fall back to
     * MobileGlues for broadest mod coverage until clean-room catches up.
     */
    fun glCompatMode(): BooxinGlCompatMode {
        if (!::prefs.isInitialized) return BooxinGlCompatMode.MAX_COMPAT
        val raw = prefs.getString(KEY_GL_COMPAT_MODE, BooxinGlCompatMode.MAX_COMPAT.name)
        return runCatching { BooxinGlCompatMode.valueOf(raw!!) }.getOrDefault(BooxinGlCompatMode.MAX_COMPAT)
    }

    fun setGlCompatMode(mode: BooxinGlCompatMode) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_GL_COMPAT_MODE, mode.name).apply()
    }

    fun isMaxGlCompat(): Boolean = glCompatMode() == BooxinGlCompatMode.MAX_COMPAT

    fun gameDirLocation(): GameDirLocation {
        if (!::prefs.isInitialized) return GameDirLocation.INTERNAL
        return GameDirLocation.fromPref(prefs.getString(KEY_GAME_DIR_LOCATION, null))
    }

    fun gameDirCustomPath(): String {
        if (!::prefs.isInitialized) return ""
        return prefs.getString(KEY_GAME_DIR_CUSTOM, "") ?: ""
    }

    fun setGameDir(location: GameDirLocation, customPath: String? = null) {
        if (!::prefs.isInitialized) return
        val edit = prefs.edit().putString(KEY_GAME_DIR_LOCATION, location.prefValue)
        if (customPath != null) {
            edit.putString(KEY_GAME_DIR_CUSTOM, customPath.trim())
        }
        edit.commit()
    }

    fun getString(key: String, default: String?): String? {
        if (!::prefs.isInitialized) return default
        return prefs.getString(key, default)
    }

    fun putString(key: String, value: String) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(key, value).commit()
    }

    fun renderDistance(): Int {
        if (!::prefs.isInitialized) return RENDER_DISTANCE_DEFAULT
        return prefs.getInt(KEY_RENDER_DISTANCE, RENDER_DISTANCE_DEFAULT)
            .coerceIn(RENDER_DISTANCE_MIN, RENDER_DISTANCE_MAX)
    }

    fun setRenderDistance(chunks: Int) {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putInt(KEY_RENDER_DISTANCE, chunks.coerceIn(RENDER_DISTANCE_MIN, RENDER_DISTANCE_MAX))
            .apply()
    }

    fun enableVsync(): Boolean {
        if (!::prefs.isInitialized) return false
        return prefs.getBoolean(KEY_VSYNC, false)
    }

    fun setEnableVsync(enabled: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_VSYNC, enabled).apply()
    }

    /**
     * FSR1 upscale (MobileGlues / REL). Default off: REL FSR remaps FBO0 and can
     * clip GUI; we scale the SurfaceTexture buffer instead.
     */
    fun fsr1Enabled(): Boolean {
        if (!::prefs.isInitialized) return false
        return prefs.getBoolean(KEY_FSR1, false)
    }

    fun setFsr1Enabled(enabled: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_FSR1, enabled).apply()
    }

    fun fancyGraphics(): Boolean {
        if (!::prefs.isInitialized) return true
        return prefs.getBoolean(KEY_FANCY_GRAPHICS, true)
    }

    fun setFancyGraphics(enabled: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_FANCY_GRAPHICS, enabled).apply()
    }

    fun masterVolumePercent(): Int {
        if (!::prefs.isInitialized) return MASTER_VOLUME_DEFAULT
        return prefs.getInt(KEY_MASTER_VOLUME, MASTER_VOLUME_DEFAULT).coerceIn(0, 100)
    }

    fun setMasterVolumePercent(percent: Int) {
        if (!::prefs.isInitialized) return
        prefs.edit().putInt(KEY_MASTER_VOLUME, percent.coerceIn(0, 100)).apply()
    }

    fun backgroundThemeId(): String {
        if (!::prefs.isInitialized) return LauncherBackgroundTheme.DEFAULT.id
        return prefs.getString(KEY_BACKGROUND_THEME, LauncherBackgroundTheme.DEFAULT.id)
            ?: LauncherBackgroundTheme.DEFAULT.id
    }

    fun backgroundTheme(): LauncherBackgroundTheme =
        LauncherBackgroundTheme.fromId(backgroundThemeId())

    fun setBackgroundTheme(theme: LauncherBackgroundTheme) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_BACKGROUND_THEME, theme.id).apply()
    }

    fun backgroundAlign(): LauncherBackgroundAlign {
        if (!::prefs.isInitialized) return LauncherBackgroundAlign()
        return LauncherBackgroundAlign(
            mode = LauncherBackgroundAlignMode.fromId(
                prefs.getString(KEY_BG_ALIGN_MODE, LauncherBackgroundAlignMode.AUTO.id)
            ),
            scaleX = prefs.getFloat(KEY_BG_ALIGN_SCALE_X, 1f).coerceIn(0.5f, 3f),
            scaleY = prefs.getFloat(KEY_BG_ALIGN_SCALE_Y, 1f).coerceIn(0.5f, 3f),
            offsetX = prefs.getFloat(KEY_BG_ALIGN_OFFSET_X, 0f).coerceIn(-0.5f, 0.5f),
            offsetY = prefs.getFloat(KEY_BG_ALIGN_OFFSET_Y, 0f).coerceIn(-0.5f, 0.5f)
        )
    }

    fun setBackgroundAlign(align: LauncherBackgroundAlign) {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putString(KEY_BG_ALIGN_MODE, align.mode.id)
            .putFloat(KEY_BG_ALIGN_SCALE_X, align.scaleX.coerceIn(0.5f, 3f))
            .putFloat(KEY_BG_ALIGN_SCALE_Y, align.scaleY.coerceIn(0.5f, 3f))
            .putFloat(KEY_BG_ALIGN_OFFSET_X, align.offsetX.coerceIn(-0.5f, 0.5f))
            .putFloat(KEY_BG_ALIGN_OFFSET_Y, align.offsetY.coerceIn(-0.5f, 0.5f))
            .apply()
    }

    fun aiPendingOutTradeNo(): String? {
        if (!::prefs.isInitialized) return null
        return prefs.getString(KEY_AI_PENDING_OUT_TRADE_NO, null)?.takeIf { it.isNotBlank() }
    }

    fun aiPendingUserKey(): String? {
        if (!::prefs.isInitialized) return null
        return prefs.getString(KEY_AI_PENDING_USER_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun setAiPendingSubscription(outTradeNo: String?, userKey: String?) {
        if (!::prefs.isInitialized) return
        val edit = prefs.edit()
        if (outTradeNo.isNullOrBlank() || userKey.isNullOrBlank()) {
            edit.remove(KEY_AI_PENDING_OUT_TRADE_NO).remove(KEY_AI_PENDING_USER_KEY)
        } else {
            edit.putString(KEY_AI_PENDING_OUT_TRADE_NO, outTradeNo.trim())
                .putString(KEY_AI_PENDING_USER_KEY, userKey.trim())
        }
        edit.apply()
    }

    fun clearAiPendingSubscription() = setAiPendingSubscription(null, null)

    fun isUserAgreementAccepted(): Boolean {
        if (!::prefs.isInitialized) return false
        return prefs.getBoolean(KEY_USER_AGREEMENT_ACCEPTED, false)
    }

    fun setUserAgreementAccepted(accepted: Boolean = true) {
        if (!::prefs.isInitialized) return
        // commit(): first-launch gate must persist before the next recreate.
        prefs.edit().putBoolean(KEY_USER_AGREEMENT_ACCEPTED, accepted).commit()
    }

    /** Bluetooth keyboard / gamepad launcher navigation (default on). */
    fun controllerNavEnabled(): Boolean {
        if (!::prefs.isInitialized) return true
        return prefs.getBoolean(KEY_CONTROLLER_NAV, true)
    }

    fun setControllerNavEnabled(enabled: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_CONTROLLER_NAV, enabled).apply()
    }
}
