package com.booxin.launcher.core.launch

/** 游戏意外退出后的结构化报告。 */
enum class GameCrashKind {
    MOD_CONFLICT,
    MISSING_DEPENDENCY,
    VRAM_OOM,
    NATIVE_INCOMPATIBLE,
    MIXIN_ERROR,
    JVM_CRASH,
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
    val summary: String,
    val detail: String,
    val exitCode: Int,
    val suspectMods: List<GameCrashSuspectMod> = emptyList(),
    val missingMods: List<GameCrashMissingMod> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
) {
    val shouldPrompt: Boolean
        get() = kind != GameCrashKind.UNKNOWN || suspectMods.isNotEmpty() ||
            missingMods.isNotEmpty() || exitCode != 0
}
