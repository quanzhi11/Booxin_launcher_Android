package com.booxin.launcher.core.launch

import android.util.Log
import com.booxin.launcher.core.LauncherPrefs
import java.io.File
import java.util.Locale

/**
 * Patch options.txt: window size, launcher graphics/audio prefs, light FPS unlock,
 * and default language (zh_cn).
 */
object GameOptionsPatch {
    private const val TAG = "BooxinLaunch"
    /** Minecraft treats >= 260 as unlimited. */
    private const val UNLIMITED_FPS = 260

    fun applyWindowOverrides(
        gameDir: File,
        width: Int,
        height: Int,
        tune: BooxinLaunchTune.Resolved? = null,
        /** REL VRAM guard: clamp mipmaps / heavy options that explode at world join. */
        relMipmapCap: Int? = null,
        /** BooxinGlues / 26.3+: prefer RenderPearl Vulkan over broken OSMesa+EGL GL. */
        preferVulkanApi: Boolean = false
    ) {
        if (width < 2 || height < 2) return
        val file = File(gameDir, "options.txt")
        val map = linkedMapOf<String, String>()
        if (file.isFile) {
            file.forEachLine { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    map[line.substring(0, idx)] = line.substring(idx + 1)
                }
            }
        }
        map["fullscreen"] = "false"
        map["overrideWidth"] = width.toString()
        map["overrideHeight"] = height.toString()
        // Default UI language: Simplified Chinese (every launch).
        map["lang"] = "zh_cn"
        // Do NOT force Unicode font: Minecraft Auto guiScale bumps odd scales
        // by +1 when forceUnicodeFont=true (e.g. 3→4) → huge phone UI.
        map["forceUnicodeFont"] = "false"
        // Restore Auto guiScale; migrate away from phone v2/v3 and unicode-era Auto.
        val scaleMarker = File(gameDir, ".booxin_gui_scale_auto_v4")
        val oldMarkers = listOf(
            File(gameDir, ".booxin_gui_scale_auto_v1"),
            File(gameDir, ".booxin_gui_scale_phone_v2"),
            File(gameDir, ".booxin_gui_scale_phone_v3")
        )
        if (!scaleMarker.isFile || oldMarkers.any { it.isFile }) {
            map["guiScale"] = "0"
            runCatching {
                scaleMarker.writeText("auto-no-force-unicode")
                oldMarkers.forEach { it.delete() }
            }
            Log.i(TAG, "restored options.txt guiScale=0 Auto (no forceUnicode) at ${gameDir.name}")
            LauncherPrefs.consumeRestoreGuiScaleToAuto()
        }
        if (preferVulkanApi) {
            // 26.x reads preferredGraphicsBackend (not preferredGraphicsApi).
            // startedCleanly=false makes the client force OpenGL after a crash;
            // pin it true so BooxinGlues stays on Vulkan.
            map["startedCleanly"] = "true"
            map.remove("preferredGraphicsApi")
            map["preferredGraphicsBackend"] = "vulkan"
            Log.i(TAG, "options.txt preferredGraphicsBackend=vulkan (BooxinGlues)")
        }
        applyLauncherPrefs(map, tune, relMipmapCap)
        applyFpsBoost(map, tune)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(map.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n")
            Log.i(
                TAG,
                "options.txt ${width}x${height} fullscreen=false " +
                    "lang=${map["lang"]} vsync=${map["enableVsync"]} maxFps=${map["maxFps"]} " +
                    "rd=${map["renderDistance"]} sim=${map["simulationDistance"]} " +
                    "mip=${map["mipmapLevels"]} backend=${map["preferredGraphicsBackend"] ?: "-"} " +
                    "at ${file.absolutePath}"
            )
        }.onFailure {
            Log.w(TAG, "options.txt patch failed: ${it.message}")
        }
    }

    /**
     * FCL/PC OptiFine often leaves a heavy shader pack enabled. Many Iris-oriented
     * packs hit OptiFine's `#include depth exceeded: 10` and hang the render thread.
     * One-shot: force shaders off so the title screen can load; user can re-enable.
     */
    fun disableOptiFineShadersOnce(gameDir: File): Boolean {
        val marker = File(gameDir, ".booxin_optifine_shaders_off_v1")
        if (marker.isFile) return false
        val shadersFile = File(gameDir, "optionsshaders.txt")
        val map = linkedMapOf<String, String>()
        if (shadersFile.isFile) {
            shadersFile.forEachLine { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }
            }
        }
        val prev = map["shaderPack"].orEmpty()
        if (prev.isNotBlank() &&
            !prev.equals("OFF", ignoreCase = true) &&
            prev != "(internal)" &&
            prev != "null"
        ) {
            Log.i(TAG, "OptiFine: disabling imported shaderPack=$prev at ${gameDir.name}")
        }
        map["shaderPack"] = "OFF"
        return runCatching {
            shadersFile.parentFile?.mkdirs()
            shadersFile.writeText(map.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
            marker.writeText("off:$prev")
            true
        }.getOrDefault(false)
    }

    private fun applyLauncherPrefs(
        map: MutableMap<String, String>,
        tune: BooxinLaunchTune.Resolved?,
        relMipmapCap: Int?
    ) {
        val rd = tune?.renderDistance ?: LauncherPrefs.renderDistance()
        val fancy = tune?.fancyGraphics ?: LauncherPrefs.fancyGraphics()
        map["renderDistance"] = rd.toString()
        map["fancyGraphics"] = fancy.toString()
        // 1.16+ OptionInstance uses ordinal ints (0=fast, 1=fancy, 2=fabulous).
        // Writing "fancy"/"decreased" → "Not a number" parse errors on 1.20+.
        map["graphicsMode"] = if (fancy) "1" else "0"

        if (tune != null) {
            map["simulationDistance"] = tune.simulationDistance.toString()
            map["particles"] = particlesOrdinal(tune.particles)
            // renderClouds is the live key; keep legacy aliases for older jars.
            val clouds = renderCloudsValue(tune.clouds)
            map["renderClouds"] = clouds
            map["clouds"] = clouds
            map["cloudStatus"] = when (tune.clouds) {
                "fancy" -> "fancy"
                "fast" -> "fast"
                else -> "off"
            }
            map["entityShadows"] = tune.entityShadows.toString()
            map["ao"] = if (tune.ambientOcclusion) "true" else "false"
            map["ambientOcclusion"] = if (tune.ambientOcclusion) "true" else "false"
            map["entityDistanceScaling"] =
                String.format(Locale.US, "%.2f", tune.entityDistanceScaling)
            map["mipmapLevels"] = tune.mipmapLevels.toString()
            map["prioritizeChunkUpdates"] = tune.prioritizeChunkUpdates.toString()
        } else {
            val sim = (rd - 2).coerceIn(2, rd)
            map["simulationDistance"] = sim.toString()
            if (map["particles"] != null && map["particles"]!!.toIntOrNull() == null) {
                map["particles"] = particlesOrdinal(map["particles"]!!)
            }
            if (map["graphicsMode"] != null && map["graphicsMode"]!!.toIntOrNull() == null) {
                map["graphicsMode"] = graphicsOrdinal(map["graphicsMode"]!!)
            }
        }

        if (relMipmapCap != null) {
            val current = map["mipmapLevels"]?.toIntOrNull() ?: 4
            map["mipmapLevels"] = minOf(current, relMipmapCap).coerceAtLeast(0).toString()
            // Extra atlas levels are a common Adreno OOM trigger at world join.
            val p = map["particles"]
            if (p == null || p == "all" || p == "0") {
                map["particles"] = "1" // decreased
            }
        }

        val volume = (LauncherPrefs.masterVolumePercent() / 100.0).coerceIn(0.0, 1.0)
        map["soundCategory_master"] = String.format(Locale.US, "%.2f", volume)
    }

    /** 0=all, 1=decreased, 2=minimal (Minecraft OptionInstance ordinal). */
    private fun particlesOrdinal(raw: String): String = when (raw.trim().lowercase(Locale.US)) {
        "0", "all" -> "0"
        "2", "minimal", "min" -> "2"
        else -> "1" // decreased
    }

    /** 0=fast, 1=fancy, 2=fabulous. */
    private fun graphicsOrdinal(raw: String): String = when (raw.trim().lowercase(Locale.US)) {
        "0", "fast", "false" -> "0"
        "2", "fabulous" -> "2"
        else -> "1" // fancy
    }

    private fun renderCloudsValue(raw: String): String = when (raw.trim().lowercase(Locale.US)) {
        "fancy", "true" -> "true"
        "fast" -> "fast"
        else -> "false"
    }

    /**
     * Respect user / tune vsync; when off, raise a finite maxFps cap for headroom.
     */
    private fun applyFpsBoost(
        map: MutableMap<String, String>,
        tune: BooxinLaunchTune.Resolved?
    ) {
        val vsync = tune?.enableVsync ?: LauncherPrefs.enableVsync()
        map["enableVsync"] = vsync.toString()
        map["vsync"] = vsync.toString()
        if (vsync) {
            map["maxFps"] = (tune?.maxFps ?: 60).coerceAtMost(UNLIMITED_FPS).toString()
            return
        }

        val target = tune?.maxFps
        if (target != null) {
            map["maxFps"] = target.coerceAtMost(UNLIMITED_FPS).toString()
            return
        }

        val raw = map["maxFps"]
        val current = raw?.toIntOrNull()
        val boosted = when {
            current == null -> 120
            current >= UNLIMITED_FPS -> UNLIMITED_FPS
            else -> (current + 10).coerceAtMost(UNLIMITED_FPS)
        }
        map["maxFps"] = boosted.toString()
    }
}
