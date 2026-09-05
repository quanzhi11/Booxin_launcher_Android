package com.booxin.launcher.core.launch

/** 游戏意外退出后的结构化报告。 */
enum class GameCrashKind {
    MOD_CONFLICT,
    MISSING_DEPENDENCY,
    VRAM_OOM,
    NATIVE_INCOMPATIBLE,
    MIXIN_ERROR,
    JVM_CRASH,
    /** 进程被系统/OEM 杀掉，无 Java 崩溃报告（如静默闪退）。 */
    PROCESS_DIED,
    POJAV_SODIUM,
    UNKNOWN
}

data class GameCrashSuspectMod(
    val displayName: String,
    val fileName: String,
    val modId: String?,
    val enabled: Boolean = true,
    val reason: String? = null
)

data class GameCrashMissingMod(
    val modId: String,
    val displayHint: String
)

data class GameCrashReport(
    val versionId: String,
    val kind: GameCrashKind,
    /** 报错原因（弹窗上方）。 */
    val summary: String,
    /** 修复建议（原因下方）。 */
    val suggestion: String,
    /** 可转发的日志正文（含原因/建议/摘要）。 */
    val detail: String,
    val exitCode: Int,
    val suspectMods: List<GameCrashSuspectMod> = emptyList(),
    val missingMods: List<GameCrashMissingMod> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
) {
    /** 凡是进过 HotSpot / 有退出码 / 有可疑项，都提示用户。 */
    val shouldPrompt: Boolean
        get() = true

    fun buildShareText(): String = buildString {
        appendLine("=== Booxin 游戏意外退出 ===")
        appendLine("版本: $versionId")
        appendLine("退出码: $exitCode")
        appendLine("类型: $kind")
        appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(timestampMs))}")
        appendLine()
        appendLine("【报错原因】")
        appendLine(summary)
        appendLine()
        appendLine("【修复建议】")
        appendLine(suggestion)
        if (suspectMods.isNotEmpty()) {
            appendLine()
            appendLine("【可疑模组】")
            suspectMods.forEach { appendLine("- ${it.displayName}${it.reason?.let { r -> " ($r)" }.orEmpty()}") }
        }
        if (missingMods.isNotEmpty()) {
            appendLine()
            appendLine("【缺失依赖】")
            missingMods.forEach { appendLine("- ${it.displayHint}") }
        }
        appendLine()
        appendLine("【日志】")
        append(detail)
    }
}
