package com.booxin.launcher.core.launch

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.version.AndroidIncompatibleMods
import com.booxin.launcher.core.version.VersionModsManager
import java.io.File

/** 从崩溃日志 / 退出码推断原因与可疑模组。 */
object GameCrashAnalyzer {

    private val missingModPatterns = listOf(
        Regex("""requires any version of mod ['"]?([\w.-]+)['"]? but none""", RegexOption.IGNORE_CASE),
        Regex("""requires mod ['"]?([\w.-]+)['"]? which is missing""", RegexOption.IGNORE_CASE),
        Regex("""mod ['"]?([\w.-]+)['"]? is required but missing""", RegexOption.IGNORE_CASE),
        Regex("""Unmet dependency[^'"]*['"]?([\w.-]+)['"]?""", RegexOption.IGNORE_CASE),
        Regex("""could not find required mod ['"]?([\w.-]+)['"]?""", RegexOption.IGNORE_CASE),
        Regex("""MissingModsException[^'"]*['"]?([\w.-]+)['"]?""", RegexOption.IGNORE_CASE)
    )

    private val modIdInText = Regex("""mod ['"]?([\w.-]+)['"]?""", RegexOption.IGNORE_CASE)
    private val providedByMod = Regex(
        """provided by ['"]([\w.-]+)['"]""",
        RegexOption.IGNORE_CASE
    )

    fun analyze(
        versionId: String,
        exitCode: Int,
        gameWasRunning: Boolean,
        /** Main-process recovery after `:game` SIGKILL / silent death. */
        forceUnexpected: Boolean = false
    ): GameCrashReport? {
        val text = collectCrashText(versionId)
        if (!shouldReport(exitCode, gameWasRunning, text, forceUnexpected)) return null

        val missingMods = extractMissingMods(text)
        val kind = classify(text, exitCode, missingMods, gameWasRunning, forceUnexpected)
        val suspectMods = extractSuspectMods(versionId, text, kind)
        val summary = buildSummary(kind, missingMods, suspectMods, exitCode, text)
        val suggestion = buildSuggestion(kind, missingMods, suspectMods, text)
        val detail = buildDetail(text, exitCode, gameWasRunning)

        return GameCrashReport(
            versionId = versionId,
            kind = kind,
            summary = summary,
            suggestion = suggestion,
            detail = detail,
            exitCode = exitCode,
            suspectMods = suspectMods,
            missingMods = missingMods
        )
    }

    fun collectCrashText(versionId: String): String {
        val cutoff = System.currentTimeMillis() - 5 * 60_000L
        val chunks = ArrayList<String>()
        val versionRoot = File(LauncherPaths.versionsDir, versionId)
        File(versionRoot, "crash-reports").listFiles()
            ?.filter { it.isFile && it.lastModified() >= cutoff }
            ?.sortedByDescending { it.lastModified() }
            ?.take(3)
            ?.forEach { f ->
                runCatching { chunks += f.readText().take(120_000) }
            }
        versionRoot.listFiles()
            ?.filter {
                it.isFile && it.lastModified() >= cutoff &&
                    (it.name.startsWith("hs_err_pid") || it.name.contains("crash", ignoreCase = true))
            }
            ?.sortedByDescending { it.lastModified() }
            ?.take(2)
            ?.forEach { f ->
                runCatching { chunks += f.readText().take(80_000) }
            }
        runCatching {
            val launchLog = File(LauncherPaths.rootDir, "logs/latest-launch.log")
            if (launchLog.isFile && launchLog.lastModified() >= cutoff) {
                chunks += launchLog.readText().takeLast(120_000)
            }
        }
        runCatching {
            val ctx = com.booxin.launcher.BooxinApp.getAppContext()
            val mirror = File(ctx.getExternalFilesDir(null), "crash/latest-launch.log")
            if (mirror.isFile && mirror.lastModified() >= cutoff) {
                chunks += mirror.readText().takeLast(60_000)
            }
        }
        return chunks.joinToString("\n")
    }

    private fun shouldReport(
        exitCode: Int,
        gameWasRunning: Boolean,
        text: String,
        forceUnexpected: Boolean
    ): Boolean {
        if (looksLikeUserExit(text)) return false
        val meaningful = stripBenignNoise(text)
        if (forceUnexpected) {
            if (hasCrashArtifacts(meaningful) || hasStrongCrashMarkers(meaningful)) return true
            // flite/Realms/sysfs only → not a real crash after user leave / silent stop
            if (isBenignNoiseOnly(text)) return false
            return true
        }
        if (!gameWasRunning && exitCode == 0 && meaningful.isBlank()) return false
        if (!gameWasRunning && exitCode == 0 && !hasStrongCrashMarkers(meaningful) && !hasCrashArtifacts(meaningful)) {
            return false
        }
        if (gameWasRunning) {
            if (hasCrashArtifacts(meaningful) || hasStrongCrashMarkers(meaningful)) return true
            return false
        }
        return exitCode != 0 || hasStrongCrashMarkers(meaningful) || hasCrashArtifacts(meaningful)
    }

    private fun looksLikeUserExit(text: String): Boolean {
        if (text.isBlank()) return false
        return "user_exit intentional" in text ||
            "user_stop" in text ||
            "结束游戏进程: user_stop" in text ||
            "killProcess :game (user_stop)" in text
    }

    private fun isBenignNoiseOnly(text: String): Boolean {
        if (text.isBlank()) return true
        val meaningful = stripBenignNoise(text)
        return !hasCrashArtifacts(meaningful) && !hasStrongCrashMarkers(meaningful)
    }

    private fun stripBenignNoise(text: String): String {
        if (text.isBlank()) return text
        return text.lineSequence().filterNot { line ->
            val l = line.lowercase()
            "libflite" in l ||
                "flite.so" in l ||
                ("narrator" in l && ("unsatisfied" in l || "failed to load" in l || "initializeexception" in l)) ||
                "text2speech" in l ||
                "realms" in l ||
                "signedjwt" in l ||
                "bus_dcvs" in l ||
                "cur_freq" in l ||
                ("accessdeniedexception" in l && "/sys/" in l) ||
                "pack declares support for version newer" in l ||
                "booxin offline skin" in l ||
                // Known non-fatal on OEM/Forge deferred bridge load — game often continues.
                ("already loaded in another classloader" in l &&
                    ("booxin_bridge" in l || "libbooxin" in l || "pojav" in l)) ||
                ("unsatisfiedlinkerror" in l &&
                    ("booxin_bridge" in l || "libbooxin" in l) &&
                    "already loaded" in l) ||
                ("system.load" in l && "booxin_bridge" in l && "already loaded" in l)
        }.joinToString("\n")
    }

    private fun hasCrashArtifacts(text: String): Boolean =
        "---- Minecraft Crash Report ----" in text ||
            "A detailed walkthrough of the error" in text ||
            "# A fatal error has been detected by the Java Runtime Environment" in text

    /** Markers that mean a real failure — not narrator flite / bridge already-loaded. */
    private fun hasStrongCrashMarkers(text: String): Boolean {
        if (text.isBlank()) return false
        val markers = listOf(
            "ExceptionInInitializerError",
            "ModResolutionException",
            "Mixin apply failed",
            "MixinTransformerError",
            "OutOfMemoryError",
            "SIGSEGV",
            "SIGABRT",
            "FATAL ERROR",
            "Game crashed",
            "---- Minecraft Crash Report ----",
            "using PojavLauncher",
            "nglfwSetFramebufferSizeCallback"
        )
        if (markers.any { text.contains(it, ignoreCase = true) }) return true
        if (text.contains("UnsatisfiedLinkError", ignoreCase = true) ||
            text.contains("already loaded in another classloader", ignoreCase = true)
        ) {
            val lower = text.lowercase()
            val benignNative =
                "flite" in lower ||
                    "narrator" in lower ||
                    "text2speech" in lower ||
                    "booxin_bridge" in lower ||
                    "libbooxin" in lower
            if (!benignNative) return true
        }
        return false
    }

    private fun hasExceptionMarkers(text: String): Boolean = hasStrongCrashMarkers(text)

    private fun classify(
        text: String,
        exitCode: Int,
        missingMods: List<GameCrashMissingMod>,
        gameWasRunning: Boolean,
        forceUnexpected: Boolean
    ): GameCrashKind {
        val meaningful = stripBenignNoise(text)
        val lower = meaningful.lowercase()
        if (missingMods.isNotEmpty()) return GameCrashKind.MISSING_DEPENDENCY
        if ("pojavlauncher" in lower && "sodium" in lower) {
            return GameCrashKind.POJAV_SODIUM
        }
        if ("using pojavlauncher" in lower) {
            return GameCrashKind.POJAV_SODIUM
        }
        if ("outofmemoryerror" in lower ||
            "out of memory" in lower ||
            "gl_out_of_memory" in lower ||
            "not enough memory" in lower ||
            ("hs_err" in lower && "out of memory" in lower)
        ) {
            return GameCrashKind.VRAM_OOM
        }
        // OSHI /sys AccessDenied → ExceptionInInitializerError：先于模组冲突判定。
        if (looksLikeOshiSysfsCrash(text)) {
            return GameCrashKind.JVM_CRASH
        }
        // HotSpot fatal / SIGSEGV outranks narrators' UnsatisfiedLinkError noise.
        if ("sigsegv" in lower ||
            "sigabrt" in lower ||
            "a fatal error has been detected by the java runtime environment" in lower
        ) {
            return GameCrashKind.JVM_CRASH
        }
        if ("unsatisfiedlinkerror" in lower ||
            "em_x86_64" in lower ||
            "libimgui" in lower ||
            "can't load library" in lower
        ) {
            // Bridge already-loaded is stripped above; remaining link errors are real.
            return GameCrashKind.NATIVE_INCOMPATIBLE
        }
        if ("mixin apply failed" in lower ||
            "mixintransformererror" in lower ||
            "@mixin" in lower
        ) {
            return GameCrashKind.MIXIN_ERROR
        }
        if ("modresolutionexception" in lower ||
            ("incompatible" in lower && "mod" in lower) ||
            ("duplicate" in lower && "mod" in lower) ||
            "two mods provide" in lower ||
            // 勿把 Mixin “overwrite conflict” 误判成模组冲突。
            ("conflict" in lower && "mod" in lower && "overwrite conflict" !in lower)
        ) {
            return GameCrashKind.MOD_CONFLICT
        }
        if (hasCrashArtifacts(meaningful) || hasExceptionMarkers(meaningful)) {
            return GameCrashKind.JVM_CRASH
        }
        if ((forceUnexpected || gameWasRunning) && !hasCrashArtifacts(meaningful)) {
            return GameCrashKind.PROCESS_DIED
        }
        if (exitCode != 0) return GameCrashKind.JVM_CRASH
        return GameCrashKind.UNKNOWN
    }

    private fun extractMissingMods(text: String): List<GameCrashMissingMod> {
        if (text.isBlank()) return emptyList()
        val found = linkedMapOf<String, GameCrashMissingMod>()
        for (pattern in missingModPatterns) {
            pattern.findAll(text).forEach { match ->
                val id = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (id.isNotBlank() && id.length >= 2) {
                    found.putIfAbsent(
                        id.lowercase(),
                        GameCrashMissingMod(modId = id, displayHint = id)
                    )
                }
            }
        }
        return found.values.toList()
    }

    private fun extractSuspectMods(
        versionId: String,
        text: String,
        kind: GameCrashKind
    ): List<GameCrashSuspectMod> {
        if (text.isBlank()) return emptyList()
        val lower = text.lowercase()
        val mods = VersionModsManager.list(versionId).filter { it.enabled }
        val hits = linkedMapOf<String, GameCrashSuspectMod>()

        for (mod in mods) {
            val modId = AndroidIncompatibleMods.match(mod)?.modId
            val fileStem = mod.displayName
                .removeSuffix(".jar")
                .lowercase()
            val matched = (modId != null && modId.lowercase() in lower) ||
                fileStem in lower ||
                mod.displayName.lowercase() in lower ||
                AndroidIncompatibleMods.match(mod) != null &&
                kind == GameCrashKind.NATIVE_INCOMPATIBLE &&
                looksNativeForMod(lower, modId, fileStem)
            if (matched) {
                val reason = AndroidIncompatibleMods.match(mod)?.reason
                hits[mod.file.name] = GameCrashSuspectMod(
                    displayName = mod.displayName,
                    fileName = mod.file.name,
                    modId = modId,
                    enabled = mod.enabled,
                    reason = reason
                )
            }
        }

        (modIdInText.findAll(text) + providedByMod.findAll(text)).forEach { match ->
            val id = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (id.length < 2) return@forEach
            val mod = mods.firstOrNull { m ->
                val hit = AndroidIncompatibleMods.match(m)
                hit?.modId.equals(id, ignoreCase = true) ||
                    hit?.id.equals(id, ignoreCase = true) ||
                    m.displayName.contains(id, ignoreCase = true) ||
                    m.file.name.contains(id, ignoreCase = true)
            } ?: return@forEach
            hits.putIfAbsent(
                mod.file.name,
                GameCrashSuspectMod(
                    displayName = mod.displayName,
                    fileName = mod.file.name,
                    modId = id,
                    enabled = mod.enabled,
                    reason = AndroidIncompatibleMods.match(mod)?.reason
                )
            )
        }

        Regex("""at\s+[\w.$]+\.([\w]+)\.""").findAll(text).forEach { match ->
            val pkg = match.groupValues.getOrNull(1)?.lowercase().orEmpty()
            if (pkg.length < 4) return@forEach
            mods.filter { mod ->
                val stem = mod.displayName.removeSuffix(".jar").lowercase()
                stem.contains(pkg) || pkg.contains(stem.removeSuffix(".jar"))
            }.forEach { mod ->
                hits.putIfAbsent(
                    mod.file.name,
                    GameCrashSuspectMod(
                        displayName = mod.displayName,
                        fileName = mod.file.name,
                        modId = AndroidIncompatibleMods.match(mod)?.modId,
                        enabled = mod.enabled
                    )
                )
            }
        }

        if (hits.isEmpty() && kind == GameCrashKind.NATIVE_INCOMPATIBLE) {
            for (mod in mods) {
                val hit = AndroidIncompatibleMods.match(mod) ?: continue
                hits[mod.file.name] = GameCrashSuspectMod(
                    displayName = mod.displayName,
                    fileName = mod.file.name,
                    modId = hit.modId,
                    enabled = mod.enabled,
                    reason = hit.reason
                )
            }
        }

        return hits.values
            .sortedBy { it.displayName.lowercase() }
            .take(12)
    }

    private fun looksNativeForMod(lower: String, modId: String?, fileStem: String): Boolean {
        if (modId != null && modId.lowercase() in lower) return true
        if (fileStem in lower) return true
        return false
    }

    private fun looksLikeVulkanFpsStubCrash(text: String): Boolean {
        val lower = text.lowercase()
        return "sigsegv" in lower &&
            ("vk.updatefps" in lower ||
                "updatefps" in lower ||
                "nvkqueuepresentkhr" in lower ||
                ("getandaddint" in lower && "vulkan" in lower))
    }

    private fun looksLikeAwtCrash(text: String): Boolean {
        val lower = text.lowercase()
        return "libawt_xawt" in lower ||
            "could not initialize class java.awt" in lower ||
            ("unsatisfiedlinkerror" in lower &&
                ("libawt" in lower || "awt_xawt" in lower || "headlessexception" in lower))
    }

    private fun looksLikeOshiSysfsCrash(text: String): Boolean {
        val lower = text.lowercase()
        // 补丁 jar 用过高 JDK 编译时，Java 17 客户端会报 UnsupportedClassVersionError。
        if ("unsupportedclassversionerror" in lower &&
            ("linuxcentralprocessor" in lower || "oshi/hardware" in lower || "oshi" in lower)
        ) {
            return true
        }
        if ("class file version 69" in lower && "oshi" in lower) return true
        if ("/sys/" !in lower && "accessdeniedexception" !in lower) return false
        return "oshi" in lower ||
            "linuxcentralprocessor" in lower ||
            "readtopologyfromsysfs" in lower ||
            "memlat" in lower ||
            "bus_dcvs" in lower ||
            ("exceptionininitializererror" in lower && "accessdeniedexception" in lower)
    }

    private fun buildSummary(
        kind: GameCrashKind,
        missingMods: List<GameCrashMissingMod>,
        suspectMods: List<GameCrashSuspectMod>,
        exitCode: Int,
        crashText: String = ""
    ): String = when (kind) {
        GameCrashKind.MISSING_DEPENDENCY ->
            if (missingMods.isEmpty()) "检测到模组依赖缺失"
            else "缺少依赖：${missingMods.joinToString { it.displayHint }}"
        GameCrashKind.MOD_CONFLICT ->
            if (suspectMods.isEmpty()) "疑似模组冲突导致崩溃"
            else "疑似冲突模组：${suspectMods.take(3).joinToString { it.displayName }}"
        GameCrashKind.VRAM_OOM -> "显存或内存不足导致崩溃"
        GameCrashKind.NATIVE_INCOMPATIBLE ->
            if (looksLikeAwtCrash(crashText)) {
                "AWT/Swing 初始化失败（缺少可用的软件 Toolkit）"
            } else {
                "模组/原生库加载失败（不兼容或 ClassLoader 冲突）"
            }
        GameCrashKind.MIXIN_ERROR -> "模组 Mixin 注入失败，可能与版本或其他模组冲突"
        GameCrashKind.JVM_CRASH ->
            when {
                looksLikeOshiSysfsCrash(crashText) -> "系统信息库（OSHI）加载失败"
                looksLikeVulkanFpsStubCrash(crashText) ->
                    "Vulkan 呈现时 FPS 计数地址无效（需更新启动器 native）"
                else -> "游戏进程异常退出（退出码 $exitCode）"
            }
        GameCrashKind.PROCESS_DIED -> "游戏进程被系统结束（无崩溃报告，退出码 $exitCode）"
        GameCrashKind.POJAV_SODIUM -> "Sodium 误判为 PojavLauncher 并主动退出"
        GameCrashKind.UNKNOWN -> "游戏意外退出（退出码 $exitCode）"
    }

    private fun buildSuggestion(
        kind: GameCrashKind,
        missingMods: List<GameCrashMissingMod>,
        suspectMods: List<GameCrashSuspectMod>,
        crashText: String = ""
    ): String = when (kind) {
        GameCrashKind.MISSING_DEPENDENCY ->
            if (missingMods.isEmpty()) "请安装缺失依赖模组后重试。"
            else "请安装：${missingMods.joinToString { it.displayHint }}，或点「一键下载依赖」。"
        GameCrashKind.MOD_CONFLICT ->
            if (suspectMods.isEmpty()) "尝试禁用近期新增模组后重试；也可导出日志发给开发者。"
            else "建议先禁用：${suspectMods.take(3).joinToString { it.displayName }}，再启动。"
        GameCrashKind.VRAM_OOM ->
            "降低渲染距离/分辨率，关闭高清材质与光影，或减少分配内存后重试。"
        GameCrashKind.NATIVE_INCOMPATIBLE ->
            if (looksLikeAwtCrash(crashText)) {
                val names = suspectMods.take(3).joinToString { it.displayName }
                if (names.isNotEmpty()) {
                    "可先禁用：$names；或确认启动器已启用 Cacio17 AWT 后重试。"
                } else {
                    "确认运行时已装入 Cacio17，并更新启动器后重试；仍失败再禁用相关 GUI 模组。"
                }
            } else {
                "禁用含桌面原生库的模组；若日志有 already loaded，请更新启动器到最新版后重开。"
            }
        GameCrashKind.MIXIN_ERROR ->
            "检查模组与游戏版本是否匹配，并禁用冲突模组后重试。"
        GameCrashKind.JVM_CRASH ->
            if (looksLikeOshiSysfsCrash(crashText)) {
                "请更新到最新 Booxin（OSHI Android 补丁需按 Java 17 编译）。仍复现请转发日志。"
            } else {
                "查看下方日志中的 Exception；常见处理：换渲染器、禁用可疑模组、降低画质。"
            }
        GameCrashKind.PROCESS_DIED ->
            "多为系统杀进程或显存压力。建议：关闭后台 App、降低画质/视距、关闭联机隧道后重试；仍复现请转发日志。"
        GameCrashKind.POJAV_SODIUM ->
            "请更新到最新 Booxin（会隐藏 POJAV_RENDERER）。仍出现则清缓存后重装启动器。"
        GameCrashKind.UNKNOWN ->
            "请转发下方日志给开发者；也可先降低画质或禁用近期模组试一次。"
    }

    private fun buildDetail(text: String, exitCode: Int, gameWasRunning: Boolean): String {
        if (text.isBlank()) {
            return buildString {
                appendLine("未找到详细崩溃文件。")
                appendLine("exitCode=$exitCode hotspotEntered=$gameWasRunning")
                appendLine("可转发本段文字；或到设置导出完整诊断包。")
            }
        }
        val lines = text.lines()
        val interesting = lines.filter { line ->
            val l = line.lowercase()
            l.contains("exception") ||
                l.contains("error") ||
                l.contains("caused by") ||
                l.contains("crash") ||
                l.contains("mixin") ||
                l.contains("mod ") ||
                l.contains("missing") ||
                l.contains("conflict") ||
                l.contains("memory") ||
                l.contains("pojav") ||
                l.contains("unsatisfied") ||
                l.contains("sigsegv") ||
                l.contains("already loaded") ||
                l.contains("has died")
        }
        val body = if (interesting.isNotEmpty()) {
            interesting.takeLast(40).joinToString("\n")
        } else {
            lines.takeLast(30).joinToString("\n")
        }
        return body.take(6_000)
    }
}
