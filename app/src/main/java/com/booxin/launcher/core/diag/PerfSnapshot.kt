package com.booxin.launcher.core.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.WindowManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.LauncherPrefs
import com.booxin.launcher.core.launch.BooxinLaunchTune
import com.booxin.launcher.core.launch.GameSurfaceBridge
import com.booxin.launcher.core.launch.GlRendererProfile
import com.booxin.launcher.core.runtime.RendererInstaller
import org.json.JSONObject
import java.io.File

/** Graphics / performance fields for diagnostic exports. */
object PerfSnapshot {

    private val OPTIONS_KEYS = listOf(
        "overrideWidth", "overrideHeight", "fullscreen",
        "renderDistance", "simulationDistance",
        "graphicsMode", "fancyGraphics",
        "maxFps", "enableVsync", "vsync",
        "guiScale", "entityDistanceScaling",
        "particles", "mipmapLevels", "biomeBlendRadius",
        "entityShadows", "cloudStatus", "clouds",
        "ao", "ambientOcclusion", "prioritizeChunkUpdates",
        "fullscreenVideoMode", "inactivityFpsLimit"
    )

    fun build(context: Context, launchWidth: Int = 0, launchHeight: Int = 0): String {
        return buildString {
            appendLine("=== performance snapshot ===")
            appendDevice(context)
            appendDisplay(context, launchWidth, launchHeight)
            appendLauncherPrefs()
            appendNatives()
            appendSelectedVersionOptions()
            appendSodiumHints()
            appendMinecraftLatestLogHints()
            appendChecks(context, launchWidth, launchHeight)
        }
    }

    fun launchLine(context: Context, width: Int, height: Int): String {
        val dm = context.resources.displayMetrics
        val tune = runCatching { BooxinLaunchTune.resolve(context) }.getOrNull()
        val mem = tune?.maxMemoryMb ?: LauncherPrefs.maxMemoryMb()
        val pref = LauncherPrefs.rendererPreference()
        val autoKind = AppContainer.repository.session.value.selectedVersionId
            ?.let { GlRendererProfile.forVersion(it) }
            ?.name
            ?: "?"
        val tuneLabel = tune?.let { "${it.mode.prefValue}->${it.effective.prefValue}" } ?: "?"
        return "PERF surface=${width}x${height} physical=${dm.widthPixels}x${dm.heightPixels} " +
            "density=${dm.density} refresh=${refreshHz(context)} " +
            "mem=${mem}MB tune=$tuneLabel rendererPref=$pref autoKind=$autoKind"
    }

    private fun StringBuilder.appendDevice(context: Context) {
        appendLine("-- device --")
        appendLine("manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} device=${Build.DEVICE}")
        appendLine("hardware=${Build.HARDWARE} board=${Build.BOARD}")
        if (Build.VERSION.SDK_INT >= 31) {
            appendLine("soc=${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        appendLine("abi=${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            appendLine(
                "ramTotalMb=${mi.totalMem / (1024 * 1024)} " +
                    "ramAvailMb=${mi.availMem / (1024 * 1024)} lowMem=${mi.lowMemory}"
            )
            appendLine("isLowRamDevice=${am.isLowRamDevice}")
        }
        runCatching {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val freeMb = stat.availableBytes / (1024 * 1024)
            val totalMb = stat.totalBytes / (1024 * 1024)
            appendLine("dataFreeMb=$freeMb dataTotalMb=$totalMb")
        }
        val rt = Runtime.getRuntime()
        appendLine(
            "launcherHeapMaxMb=${rt.maxMemory() / (1024 * 1024)} " +
                "usedMb=${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)}"
        )
    }

    private fun StringBuilder.appendDisplay(
        context: Context,
        launchWidth: Int,
        launchHeight: Int
    ) {
        appendLine("-- display / window --")
        val dm = context.resources.displayMetrics
        appendLine(
            "physical=${dm.widthPixels}x${dm.heightPixels} " +
                "density=${dm.density} dpi=${dm.densityDpi} " +
                "xdpi=${"%.1f".format(dm.xdpi)} ydpi=${"%.1f".format(dm.ydpi)}"
        )
        appendLine("refreshHz=${refreshHz(context)}")
        appendLine(
            "GameSurfaceBridge=${GameSurfaceBridge.width}x${GameSurfaceBridge.height} " +
                "hasSurface=${GameSurfaceBridge.hasSurface()}"
        )
        if (launchWidth > 0 && launchHeight > 0) {
            appendLine("launchWindow=${launchWidth}x${launchHeight}")
            val px = launchWidth.toLong() * launchHeight
            appendLine("launchPixels=$px (~${"%.1f".format(px / 1_000_000.0)}MP)")
        }
    }

    private fun StringBuilder.appendLauncherPrefs() {
        appendLine("-- launcher prefs --")
        appendLine("maxMemoryMb=${LauncherPrefs.maxMemoryMb()}")
        appendLine("recommendedMaxMb=${LauncherPrefs.recommendedMaxMb()}")
        appendLine("launchTuneMode=${LauncherPrefs.launchTuneMode().prefValue}")
        val pref = LauncherPrefs.rendererPreference()
        val kind = LauncherPrefs.rendererKind()
        appendLine("rendererPreference=$pref")
        appendLine("rendererKind=${kind?.name ?: "(auto)"} display=${kind?.displayName ?: "auto"}")
        val selected = AppContainer.repository.session.value.selectedVersionId
        if (selected != null) {
            val auto = GlRendererProfile.forVersion(selected)
            appendLine("selectedVersion=$selected autoRenderer=${auto.name}")
            if (kind != null && kind.requiresPlugin) {
                appendLine("pluginInstalled=${RendererInstaller.isInstalled(kind)}")
            }
        }
    }

    private fun StringBuilder.appendNatives() {
        appendLine("-- staged natives --")
        if (!LauncherPaths.isInitialized) {
            appendLine("(paths unavailable)")
            return
        }
        val dir = File(LauncherPaths.runtimeDir, "natives")
        appendLine("dir=${dir.absolutePath} exists=${dir.isDirectory}")
        val marker = File(dir, ".ready")
        appendLine(
            "marker=${
                if (marker.isFile) marker.readText().trim().take(120) else "(missing)"
            }"
        )
        for (name in listOf(
            "libbooxin_bridge.so",
            "libbooxin_bridge.so",
            "libpojavexec.so", // legacy alias copy, if still staged

            "libSDL3.so",
            "libmobileglues.so",
            "libgl4es_114.so",
            "libgl4es_holy.so",
            "liblwjgl.so"
        )) {
            val f = File(dir, name)
            if (f.isFile) {
                appendLine("$name bytes=${f.length()} mtime=${f.lastModified()}")
            } else {
                appendLine("$name=(missing)")
            }
        }
    }

    private fun StringBuilder.appendSelectedVersionOptions() {
        appendLine("-- options.txt (selected + recent) --")
        if (!LauncherPaths.isInitialized) {
            appendLine("(paths unavailable)")
            return
        }
        val selected = AppContainer.repository.session.value.selectedVersionId
        val ids = linkedSetOf<String>()
        if (!selected.isNullOrBlank()) ids += selected
        AppContainer.repository.installedVersions.value
            .sortedByDescending { File(LauncherPaths.versionsDir, it.id).lastModified() }
            .map { it.id }
            .take(4)
            .forEach { ids += it }
        if (ids.isEmpty()) {
            appendLine("(no installed versions)")
            return
        }
        for (id in ids) {
            val file = File(LauncherPaths.versionsDir, "$id/options.txt")
            appendLine("--- $id ---")
            if (!file.isFile) {
                appendLine("(no options.txt)")
                continue
            }
            val map = readOptionsMap(file)
            for (key in OPTIONS_KEYS) {
                map[key]?.let { appendLine("$key=$it") }
            }
            map.keys.filter {
                it.contains("scale", ignoreCase = true) ||
                    it.contains("render", ignoreCase = true) ||
                    it.contains("fps", ignoreCase = true)
            }.filter { it !in OPTIONS_KEYS }.sorted().forEach { key ->
                appendLine("$key=${map[key]}")
            }
        }
    }

    private fun StringBuilder.appendSodiumHints() {
        appendLine("-- sodium / iris config (if any) --")
        if (!LauncherPaths.isInitialized) return
        val selected = AppContainer.repository.session.value.selectedVersionId ?: return
        val verDir = File(LauncherPaths.versionsDir, selected)
        val candidates = listOf(
            File(verDir, "config/sodium-options.json"),
            File(verDir, "config/sodium-mixins.properties"),
            File(verDir, "config/iris.properties"),
            File(verDir, "config/iris-included.properties")
        )
        var any = false
        for (f in candidates) {
            if (!f.isFile) continue
            any = true
            appendLine("--- ${f.name} (${f.length()} bytes) ---")
            val text = runCatching { f.readText() }.getOrElse { "read failed: ${it.message}" }
            if (f.name.endsWith(".json")) {
                appendLine(summarizeSodiumJson(text))
            } else {
                appendLine(text.lineSequence().take(40).joinToString("\n"))
            }
        }
        if (!any) appendLine("(none found under version config/)")
    }

    private fun summarizeSodiumJson(text: String): String {
        return runCatching {
            val root = JSONObject(text)
            val quality = root.optJSONObject("quality")
            val perf = root.optJSONObject("performance")
            val adv = root.optJSONObject("advanced")
            buildString {
                quality?.keys()?.forEach { appendLine("quality.$it=${quality.opt(it)}") }
                perf?.keys()?.forEach { appendLine("performance.$it=${perf.opt(it)}") }
                adv?.keys()?.forEach { appendLine("advanced.$it=${adv.opt(it)}") }
                if (isEmpty()) append(text.take(1500))
            }
        }.getOrElse { text.take(1500) }
    }

    private fun StringBuilder.appendMinecraftLatestLogHints() {
        appendLine("-- minecraft latest.log hints --")
        if (!LauncherPaths.isInitialized) {
            appendLine("(paths unavailable)")
            return
        }
        val selected = AppContainer.repository.session.value.selectedVersionId
        val logFiles = mutableListOf<File>()
        if (!selected.isNullOrBlank()) {
            logFiles += File(LauncherPaths.versionsDir, "$selected/logs/latest.log")
        }
        logFiles += File(LauncherPaths.rootDir, "logs/latest.log")
        val log = logFiles.firstOrNull { it.isFile }
        if (log == null) {
            appendLine("(no latest.log)")
            return
        }
        appendLine("file=${log.absolutePath} bytes=${log.length()}")
        val lines = runCatching { log.readLines() }.getOrElse { emptyList() }
        val interesting = lines.filter { line ->
            val l = line.lowercase()
            "fps" in l || "opengl" in l || "renderer" in l || "mobileglues" in l ||
                "gl4es" in l || "sodium" in l || "iris" in l || "vsync" in l ||
                "resolution" in l || "framebuffer" in l || "oom" in l ||
                "out of memory" in l || "lag" in l || "can't keep up" in l
        }.takeLast(40)
        if (interesting.isEmpty()) {
            appendLine("(no fps/renderer/memory hint lines in last read)")
        } else {
            interesting.forEach { appendLine(it) }
        }
    }

    private fun StringBuilder.appendChecks(context: Context, launchWidth: Int, launchHeight: Int) {
        appendLine("-- checks --")
        val flags = mutableListOf<String>()
        val dm = context.resources.displayMetrics
        val w = if (launchWidth > 1) launchWidth else GameSurfaceBridge.width.coerceAtLeast(dm.widthPixels)
        val h = if (launchHeight > 1) launchHeight else GameSurfaceBridge.height.coerceAtLeast(dm.heightPixels)
        val px = w.toLong() * h
        if (px >= 2_500_000L) flags += "high_res ${w}x${h}"
        if (px >= 3_500_000L) flags += "very_high_res ${w}x${h}"
        val mem = LauncherPrefs.maxMemoryMb()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val totalMb = mi.totalMem / (1024 * 1024)
            if (mem > totalMb * 0.45) flags += "heap_high xmx=${mem}MB ram=${totalMb}MB"
            if (mem < 1024 && totalMb >= 4096) flags += "heap_low xmx=${mem}MB"
            if (mi.lowMemory) flags += "device_low_memory"
        }
        val selected = AppContainer.repository.session.value.selectedVersionId
        if (!selected.isNullOrBlank()) {
            val opts = readOptionsMap(File(LauncherPaths.versionsDir, "$selected/options.txt"))
            val rd = opts["renderDistance"]?.toIntOrNull()
            if (rd != null && rd >= 12) flags += "render_distance=$rd"
            val sd = opts["simulationDistance"]?.toIntOrNull()
            if (sd != null && sd >= 12) flags += "simulation_distance=$sd"
            val graphics = opts["graphicsMode"] ?: opts["fancyGraphics"]
            if (graphics.equals("fabulous", true) || graphics.equals("true", true)) {
                flags += "graphics=$graphics"
            }
            val maxFps = opts["maxFps"]?.toIntOrNull()
            if (maxFps != null && maxFps >= 260) flags += "max_fps=$maxFps"
            val ow = opts["overrideWidth"]?.toIntOrNull()
            val oh = opts["overrideHeight"]?.toIntOrNull()
            if (ow != null && oh != null && ow.toLong() * oh >= 2_500_000L) {
                flags += "options_override=${ow}x${oh}"
            }
        }
        if (flags.isEmpty()) appendLine("(none)")
        else flags.forEach { appendLine(it) }
    }

    private fun refreshHz(context: Context): String {
        return runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val hz = if (Build.VERSION.SDK_INT >= 30) {
                context.display?.mode?.refreshRate
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.mode.refreshRate
            }
            hz?.let { "%.1f".format(it) } ?: "?"
        }.getOrDefault("?")
    }

    private fun readOptionsMap(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val map = linkedMapOf<String, String>()
        runCatching {
            file.forEachLine { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    map[line.substring(0, idx)] = line.substring(idx + 1)
                }
            }
        }
        return map
    }
}
