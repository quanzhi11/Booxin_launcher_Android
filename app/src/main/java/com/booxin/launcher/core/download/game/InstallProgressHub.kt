package com.booxin.launcher.core.download.game

import com.booxin.launcher.AppContainer
import com.booxin.launcher.core.java.JavaInstallProgress
import com.booxin.launcher.core.java.JavaInstallState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Merges vanilla / loader / Java install progress for the global status bar.
 */
object InstallProgressHub {

    data class Snapshot(
        val title: String,
        val message: String,
        val fraction: Float,
        val active: Boolean
    )

    fun snapshots(): Flow<Snapshot?> = combine(
        AppContainer.repository.installProgress,
        AppContainer.repository.forgeInstallProgress,
        AppContainer.repository.fabricInstallProgress,
        AppContainer.repository.quiltInstallProgress,
        AppContainer.repository.optiFineInstallProgress,
        AppContainer.javaEnvironment.progress
    ) { values ->
        val vanilla = values[0] as GameInstallProgress?
        val forge = values[1] as GameInstallProgress?
        val fabric = values[2] as GameInstallProgress?
        val quilt = values[3] as GameInstallProgress?
        val optiFine = values[4] as GameInstallProgress?
        val java = values[5] as JavaInstallProgress?

        pickGame(optiFine)
            ?: pickGame(quilt)
            ?: pickGame(fabric)
            ?: pickGame(forge)
            ?: pickGame(vanilla)
            ?: pickJava(java)
    }

    private fun pickGame(progress: GameInstallProgress?): Snapshot? {
        progress ?: return null
        if (progress.phase == GameInstallPhase.IDLE ||
            progress.phase == GameInstallPhase.DONE ||
            progress.phase == GameInstallPhase.FAILED
        ) {
            return null
        }
        val title = progress.versionId.ifBlank { "安装中" }
        return Snapshot(
            title = title,
            message = progress.message.ifBlank { phaseLabel(progress.phase) },
            fraction = progress.fraction,
            active = true
        )
    }

    private fun pickJava(progress: JavaInstallProgress?): Snapshot? {
        progress ?: return null
        if (progress.state == JavaInstallState.INSTALLED ||
            progress.state == JavaInstallState.FAILED ||
            progress.state == JavaInstallState.NOT_INSTALLED
        ) {
            return null
        }
        return Snapshot(
            title = "Java",
            message = progress.message.ifBlank { "正在准备 Java…" },
            fraction = progress.progressFraction,
            active = true
        )
    }

    private fun phaseLabel(phase: GameInstallPhase): String = when (phase) {
        GameInstallPhase.MANIFEST -> "解析版本清单…"
        GameInstallPhase.VERSION_JSON -> "下载版本元数据…"
        GameInstallPhase.CLIENT -> "下载客户端…"
        GameInstallPhase.LIBRARIES -> "下载依赖库…"
        GameInstallPhase.MODLOADER -> "安装模组加载器…"
        GameInstallPhase.ASSETS -> "下载游戏资源…"
        else -> "安装中…"
    }
}
