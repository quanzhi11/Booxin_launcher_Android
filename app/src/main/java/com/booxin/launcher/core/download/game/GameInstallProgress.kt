package com.booxin.launcher.core.download.game

enum class GameInstallPhase {
    IDLE,
    MANIFEST,
    VERSION_JSON,
    CLIENT,
    LIBRARIES,
    MODLOADER,
    ASSETS,
    DONE,
    FAILED
}

data class GameInstallProgress(
    val versionId: String,
    val phase: GameInstallPhase,
    val message: String = "",
    val completed: Int = 0,
    val total: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L
) {
    val fraction: Float
        get() = when {
            total > 0 -> (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> -1f
        }
}
