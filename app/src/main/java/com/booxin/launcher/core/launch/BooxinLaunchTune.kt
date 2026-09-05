package com.booxin.launcher.core.launch

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.booxin.launcher.core.LauncherPrefs
import kotlin.math.max
import kotlin.math.min

/**
 * Booxin launch tuning: device-aware presets that adjust heap, vanilla options.txt,
 * and conservative JVM flags — no mods injected.
 *
 * Applied once per launch in a fixed order so the path stays deterministic.
 */
object BooxinLaunchTune {
    private const val TAG = "BooxinLaunchTune"

    enum class Mode(val prefValue: String) {
        /** Pick SMOOTH / BALANCED / QUALITY from device RAM. */
        AUTO("auto"),
        /** Favor FPS / lower load. */
        SMOOTH("smooth"),
        /** Default trade-off. */
        BALANCED("balanced"),
        /** Higher visuals when the device can take it. */
        QUALITY("quality"),
        /** Keep whatever the user set on the individual sliders. */
        CUSTOM("custom");

        companion object {
            fun fromPref(raw: String?): Mode {
                if (raw.isNullOrBlank()) return AUTO
                return entries.firstOrNull { it.prefValue.equals(raw, ignoreCase = true) } ?: AUTO
            }
        }
    }

    enum class DeviceTier {
        LOW, MID, HIGH
    }

    data class Resolved(
        val mode: Mode,
        /** Effective preset after AUTO resolution (never AUTO). */
        val effective: Mode,
        val deviceTier: DeviceTier,
        val totalRamMb: Long,
        val maxMemoryMb: Int,
        val renderDistance: Int,
        val simulationDistance: Int,
        val fancyGraphics: Boolean,
        val enableVsync: Boolean,
        val maxFps: Int,
        val particles: String,
        val clouds: String,
        val entityShadows: Boolean,
        val ambientOcclusion: Boolean,
        val entityDistanceScaling: Double,
        val mipmapLevels: Int,
        val prioritizeChunkUpdates: Int,
        /**
         * Game framebuffer scale vs physical surface (1.0 = native).
         * Low-end / 流畅 use &lt;1 to cut fill-rate — biggest FPS lever without mods.
         */
        val resolutionScale: Float,
        val summaryZh: String
    )

    fun deviceTier(context: Context): DeviceTier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am?.isLowRamDevice == true) return DeviceTier.LOW
        val total = totalRamMb(context)
        return when {
            total > 0L && total < 4096L -> DeviceTier.LOW
            total >= 8192L -> DeviceTier.HIGH
            else -> DeviceTier.MID
        }
    }

    /**
     * Scale physical surface → game FBO size. Returns null if scale is ~1.
     */
    fun scaledWindow(width: Int, height: Int, scale: Float): Pair<Int, Int>? {
        if (width < 2 || height < 2) return null
        val s = scale.coerceIn(0.25f, 1f)
        if (s >= 0.98f) return null
        val w = (width * s).toInt().coerceAtLeast(480)
        val h = (height * s).toInt().coerceAtLeast(270)
        if (w == width && h == height) return null
        return w to h
    }

    fun totalRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024L * 1024L)
    }

    /**
     * Resolve the effective tune for this launch.
     * CUSTOM keeps current LauncherPrefs values; other modes use preset tables,
     * with memory still clamped to device-safe bounds.
     */
    fun resolve(context: Context): Resolved {
        val tier = deviceTier(context)
        val totalRam = totalRamMb(context)
        val selected = LauncherPrefs.launchTuneMode()
        val effective = when (selected) {
            Mode.AUTO -> when (tier) {
                DeviceTier.LOW -> Mode.SMOOTH
                DeviceTier.MID -> Mode.BALANCED
                DeviceTier.HIGH -> Mode.QUALITY
            }
            else -> selected
        }

        val preset = if (effective == Mode.CUSTOM) {
            customFromPrefs(context, totalRam, tier)
        } else {
            presetFor(effective, context, totalRam, tier)
        }

        Log.i(
            TAG,
            "resolve mode=${selected.prefValue} effective=${effective.prefValue} " +
                "tier=$tier ram=${totalRam}MB xmx=${preset.maxMemoryMb} rd=${preset.renderDistance}"
        )
        return preset.copy(mode = selected, effective = effective)
    }

    /**
     * When the user picks a named preset in Settings, write matching prefs so
     * sliders stay in sync before the next launch.
     */
    fun applyPresetToPrefs(context: Context, mode: Mode) {
        if (mode == Mode.CUSTOM || mode == Mode.AUTO) {
            LauncherPrefs.setLaunchTuneMode(mode)
            return
        }
        val resolved = presetFor(mode, context, totalRamMb(context), deviceTier(context))
        LauncherPrefs.setLaunchTuneMode(mode)
        LauncherPrefs.setMaxMemoryMb(resolved.maxMemoryMb)
        LauncherPrefs.setRenderDistance(resolved.renderDistance)
        LauncherPrefs.setFancyGraphics(resolved.fancyGraphics)
        LauncherPrefs.setEnableVsync(resolved.enableVsync)
    }

    /** Conservative HotSpot flags for Android OpenJDK (heap already set elsewhere). */
    fun jvmPerformanceArgs(maxMemoryMb: Int): List<String> {
        val regionMb = when {
            maxMemoryMb >= 4096 -> 8
            maxMemoryMb >= 2048 -> 4
            else -> 2
        }
        val codeCacheMb = when {
            maxMemoryMb >= 3072 -> 128
            maxMemoryMb >= 1536 -> 96
            else -> 64
        }
        // Keep this set small: exotic -XX can abort CreateJavaVM on some mobile JREs.
        return listOf(
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=40",
            "-XX:G1HeapRegionSize=${regionMb}m",
            "-XX:+ParallelRefProcEnabled",
            "-XX:ReservedCodeCacheSize=${codeCacheMb}m"
        )
    }

    fun forceVsyncEnv(enableVsync: Boolean): String = if (enableVsync) "true" else "false"

    private fun customFromPrefs(
        context: Context,
        totalRamMb: Long,
        tier: DeviceTier
    ): Resolved {
        val mem = LauncherPrefs.clampMemory(
            safeHeapMb(context, LauncherPrefs.maxMemoryMb(), totalRamMb)
        )
        val rd = LauncherPrefs.renderDistance()
        val sim = (rd - 2).coerceIn(2, rd)
        val scale = when (tier) {
            DeviceTier.LOW -> 0.5f
            else -> 1f
        }
        return Resolved(
            mode = Mode.CUSTOM,
            effective = Mode.CUSTOM,
            deviceTier = tier,
            totalRamMb = totalRamMb,
            maxMemoryMb = mem,
            renderDistance = rd,
            simulationDistance = sim,
            fancyGraphics = LauncherPrefs.fancyGraphics(),
            enableVsync = LauncherPrefs.enableVsync(),
            maxFps = if (LauncherPrefs.enableVsync()) 60 else 120,
            particles = if (LauncherPrefs.fancyGraphics()) "decreased" else "minimal",
            clouds = if (LauncherPrefs.fancyGraphics()) "fast" else "false",
            entityShadows = LauncherPrefs.fancyGraphics(),
            ambientOcclusion = LauncherPrefs.fancyGraphics(),
            entityDistanceScaling = if (LauncherPrefs.fancyGraphics()) 1.0 else 0.75,
            mipmapLevels = if (LauncherPrefs.fancyGraphics()) 4 else 2,
            prioritizeChunkUpdates = 0,
            resolutionScale = scale,
            summaryZh = "自定义（内存 ${mem}MB，视距 $rd）"
        )
    }

    private fun presetFor(
        mode: Mode,
        context: Context,
        totalRamMb: Long,
        tier: DeviceTier
    ): Resolved {
        val base = when (mode) {
            Mode.SMOOTH -> PresetValues(
                memoryHint = when (tier) {
                    DeviceTier.LOW -> 768
                    DeviceTier.MID -> 1280
                    DeviceTier.HIGH -> 1536
                },
                renderDistance = when (tier) {
                    DeviceTier.LOW -> 2
                    DeviceTier.MID -> 4
                    DeviceTier.HIGH -> 6
                },
                simulationDistance = when (tier) {
                    DeviceTier.LOW -> 2
                    DeviceTier.MID -> 4
                    else -> 4
                },
                fancyGraphics = false,
                enableVsync = false,
                maxFps = 60,
                particles = "minimal",
                clouds = "false",
                entityShadows = false,
                ambientOcclusion = false,
                entityDistanceScaling = 0.5,
                mipmapLevels = 0,
                prioritizeChunkUpdates = 0,
                resolutionScale = when (tier) {
                    DeviceTier.LOW -> 0.35f
                    DeviceTier.MID -> 0.5f
                    DeviceTier.HIGH -> 0.65f
                },
                label = "流畅"
            )
            Mode.QUALITY -> PresetValues(
                memoryHint = when (tier) {
                    DeviceTier.LOW -> 1536
                    DeviceTier.MID -> 2560
                    DeviceTier.HIGH -> 4096
                },
                renderDistance = when (tier) {
                    DeviceTier.LOW -> 8
                    DeviceTier.MID -> 10
                    DeviceTier.HIGH -> 12
                },
                simulationDistance = when (tier) {
                    DeviceTier.LOW -> 6
                    DeviceTier.MID -> 8
                    DeviceTier.HIGH -> 10
                },
                fancyGraphics = true,
                enableVsync = false,
                maxFps = 120,
                particles = "decreased",
                clouds = "fancy",
                entityShadows = true,
                ambientOcclusion = true,
                entityDistanceScaling = 1.0,
                mipmapLevels = 4,
                prioritizeChunkUpdates = 0,
                resolutionScale = when (tier) {
                    DeviceTier.LOW -> 0.75f
                    else -> 1f
                },
                label = "画质"
            )
            else -> PresetValues(
                memoryHint = when (tier) {
                    DeviceTier.LOW -> 1024
                    DeviceTier.MID -> 2048
                    DeviceTier.HIGH -> 3072
                },
                renderDistance = when (tier) {
                    DeviceTier.LOW -> 4
                    DeviceTier.MID -> 8
                    DeviceTier.HIGH -> 10
                },
                simulationDistance = when (tier) {
                    DeviceTier.LOW -> 4
                    DeviceTier.MID -> 6
                    DeviceTier.HIGH -> 8
                },
                fancyGraphics = tier != DeviceTier.LOW,
                enableVsync = false,
                maxFps = 90,
                particles = "decreased",
                clouds = if (tier == DeviceTier.LOW) "false" else "fast",
                entityShadows = tier != DeviceTier.LOW,
                ambientOcclusion = tier != DeviceTier.LOW,
                entityDistanceScaling = if (tier == DeviceTier.LOW) 0.75 else 1.0,
                mipmapLevels = if (tier == DeviceTier.LOW) 1 else 4,
                prioritizeChunkUpdates = 0,
                resolutionScale = when (tier) {
                    DeviceTier.LOW -> 0.5f
                    DeviceTier.MID -> 0.75f
                    DeviceTier.HIGH -> 1f
                },
                label = "均衡"
            )
        }
        val mem = LauncherPrefs.clampMemory(safeHeapMb(context, base.memoryHint, totalRamMb))
        val ramLabel = if (totalRamMb > 0) "${totalRamMb}MB 内存" else "设备"
        val scalePct = (base.resolutionScale * 100).toInt()
        return Resolved(
            mode = mode,
            effective = mode,
            deviceTier = tier,
            totalRamMb = totalRamMb,
            maxMemoryMb = mem,
            renderDistance = base.renderDistance,
            simulationDistance = base.simulationDistance.coerceAtMost(base.renderDistance),
            fancyGraphics = base.fancyGraphics,
            enableVsync = base.enableVsync,
            maxFps = base.maxFps,
            particles = base.particles,
            clouds = base.clouds,
            entityShadows = base.entityShadows,
            ambientOcclusion = base.ambientOcclusion,
            entityDistanceScaling = base.entityDistanceScaling,
            mipmapLevels = base.mipmapLevels,
            prioritizeChunkUpdates = base.prioritizeChunkUpdates,
            resolutionScale = base.resolutionScale,
            summaryZh = "${base.label}（$ramLabel，-Xmx ${mem}MB，视距 ${base.renderDistance}，分辨率 ${scalePct}%）"
        )
    }

    /**
     * Cap heap to roughly 35% of device RAM (and never above recommendedMaxMb),
     * so CreateJavaVM / OOM risk stays bounded on phones.
     */
    private fun safeHeapMb(context: Context, requested: Int, totalRamMb: Long): Int {
        val recommended = runCatching { recommendedHeapMb(context) }.getOrDefault(LauncherPrefs.MEMORY_DEFAULT_MB)
        val ramCap = if (totalRamMb > 0L) {
            ((totalRamMb * 35L) / 100L).toInt().coerceAtLeast(LauncherPrefs.MEMORY_MIN_MB)
        } else {
            recommended
        }
        return min(requested, min(ramCap, recommended))
    }

    fun recommendedHeapMb(context: Context): Int {
        val total = totalRamMb(context)
        val fromRam = when {
            total <= 0L -> LauncherPrefs.MEMORY_DEFAULT_MB
            total < 3072L -> 1024
            total < 4096L -> 1536
            total < 6144L -> 2048
            total < 8192L -> 2560
            total < 12288L -> 3072
            else -> 4096
        }
        return LauncherPrefs.clampMemory(
            min(LauncherPrefs.MEMORY_MAX_MB, max(LauncherPrefs.MEMORY_MIN_MB, fromRam))
        )
    }

    private data class PresetValues(
        val memoryHint: Int,
        val renderDistance: Int,
        val simulationDistance: Int,
        val fancyGraphics: Boolean,
        val enableVsync: Boolean,
        val maxFps: Int,
        val particles: String,
        val clouds: String,
        val entityShadows: Boolean,
        val ambientOcclusion: Boolean,
        val entityDistanceScaling: Double,
        val mipmapLevels: Int,
        val prioritizeChunkUpdates: Int,
        val resolutionScale: Float,
        val label: String
    )
}
