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

    fun analyze(
        versionId: String,
        exitCode: Int,
        gameWasRunning: Boolean
    ): GameCrashReport? {
        val text = collectCrashText(versionId)
        if (!shouldReport(exitCode, gameWasRunning, text)) return null

        val missingMods = extractMissingMods(text)
        val kind = classify(text, exitCode, missingMods)
        val suspectMods = extractSuspectMods(versionId, text, kind)
        val summary = buildSummary(kind, missingMods, suspectMods)
        val detail = buildDetail(text)

        return GameCrashReport(
            versionId = versionId,
            kind = kind,
            summary = summary,
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

    private fun shouldReport(exitCode: Int, gameWasRunning: Boolean, text: String): Boolean {
        if (text.isBlank() && exitCode == 0) return false
        if (!gameWasRunning && exitCode == 0 && !hasExceptionMarkers(text)) return false
        if (gameWasRunning && exitCode == 0 && !hasExceptionMarkers(text) && !hasCrashArtifacts(text)) {
            return false
        }
        return exitCode != 0 || hasExceptionMarkers(text) || hasCrashArtifacts(text) || gameWasRunning
    }

    private fun hasCrashArtifacts(text: String): Boolean =
        "---- Minecraft Crash Report ----" in text ||
            "A detailed walkthrough of the error" in text ||
            "# A fatal error has been detected by the Java Runtime Environment" in text

    private fun hasExceptionMarkers(text: String): Boolean {
        if (text.isBlank()) return false
        val markers = listOf(
            "ExceptionInInitializerError",
            "ModResolutionException",
            "Mixin apply failed",
            "MixinTransformerError",
            "OutOfMemoryError",
            "UnsatisfiedLinkError",
            "SIGSEGV",
            "SIGABRT",
            "FATAL ERROR",
            "Game crashed",
            "Shutting down",
            "Caused by:",
            "---- Minecraft Crash Report ----"
        )
        return markers.any { text.contains(it, ignoreCase = true) }
    }

    private fun classify(
        text: String,
        exitCode: Int,
        missingMods: List<GameCrashMissingMod>
    ): GameCrashKind {
        val lower = text.lowercase()
        if (missingMods.isNotEmpty()) return GameCrashKind.MISSING_DEPENDENCY
        if ("outofmemoryerror" in lower ||
            "out of memory" in lower ||
            "gl_out_of_memory" in lower ||
            "not enough memory" in lower ||
            ("hs_err" in lower && "out of memory" in lower)
        ) {
            return GameCrashKind.VRAM_OOM
        }
        if ("unsatisfiedlinkerror" in lower ||
            "em_x86_64" in lower ||
            "libimgui" in lower ||
            "can't load library" in lower
        ) {
            return GameCrashKind.NATIVE_INCOMPATIBLE
        }
        if ("mixin apply failed" in lower ||
            "mixintransformererror" in lower ||
            "@mixin" in lower
        ) {
            return GameCrashKind.MIXIN_ERROR
        }
        if ("modresolutionexception" in lower ||
            "incompatible" in lower && "mod" in lower ||
            "conflict" in lower ||
            "duplicate" in lower && "mod" in lower ||
            "two mods provide" in lower
        ) {
            return GameCrashKind.MOD_CONFLICT
        }
        if (hasExceptionMarkers(text) || exitCode != 0) {
            return GameCrashKind.JVM_CRASH
        }
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

        modIdInText.findAll(text).forEach { match ->
            val id = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (id.length < 2) return@forEach
            val mod = mods.firstOrNull { m ->
                val mid = AndroidIncompatibleMods.match(m)?.modId
                mid.equals(id, ignoreCase = true) ||
                    m.displayName.contains(id, ignoreCase = true)
            } ?: return@forEach
            hits.putIfAbsent(
                mod.file.name,
                GameCrashSuspectMod(
                    displayName = mod.displayName,
                    fileName = mod.file.name,
                    modId = id,
                    enabled = mod.enabled
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

    private fun buildSummary(
        kind: GameCrashKind,
        missingMods: List<GameCrashMissingMod>,
        suspectMods: List<GameCrashSuspectMod>
    ): String = when (kind) {
        GameCrashKind.MISSING_DEPENDENCY ->
            if (missingMods.isEmpty()) "检测到模组依赖缺失"
            else "缺少依赖：${missingMods.joinToString { it.displayHint }}"
        GameCrashKind.MOD_CONFLICT ->
            if (suspectMods.isEmpty()) "疑似模组冲突导致崩溃"
            else "疑似冲突模组：${suspectMods.take(3).joinToString { it.displayName }}"
        GameCrashKind.VRAM_OOM -> "显存或内存不足导致崩溃"
        GameCrashKind.NATIVE_INCOMPATIBLE -> "模组含不兼容原生库（如 x86 / 桌面专用）"
        GameCrashKind.MIXIN_ERROR -> "模组 Mixin 注入失败，可能与版本或其他模组冲突"
        GameCrashKind.JVM_CRASH -> "游戏进程异常退出"
        GameCrashKind.UNKNOWN -> "游戏意外退出"
    }

    private fun buildDetail(text: String): String {
        if (text.isBlank()) return "未找到详细日志，可在设置中导出诊断包。"
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
                l.contains("memory")
        }
        val body = if (interesting.isNotEmpty()) {
            interesting.takeLast(18).joinToString("\n")
        } else {
            lines.takeLast(12).joinToString("\n")
        }
        return body.take(2_000)
    }
}
