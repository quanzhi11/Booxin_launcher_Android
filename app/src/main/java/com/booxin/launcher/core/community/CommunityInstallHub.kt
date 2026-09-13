package com.booxin.launcher.core.community

import com.booxin.launcher.core.download.game.InstallProgressHub
import com.booxin.launcher.data.model.ModpackInstallProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide community / modpack install progress so jobs on [AppContainer.appScope]
 * stay visible after leaving the community detail page (same idea as game InstallProgressHub).
 */
object CommunityInstallHub {
    private val seq = AtomicInteger(0)
    private val _job = MutableStateFlow<JobSnapshot?>(null)
    val job = _job.asStateFlow()

    data class JobSnapshot(
        val id: Int,
        val title: String,
        val progress: ModpackInstallProgress,
        val active: Boolean
    )

    fun begin(title: String): Int {
        val id = seq.incrementAndGet()
        _job.value = JobSnapshot(
            id = id,
            title = title.ifBlank { "社区下载" },
            progress = ModpackInstallProgress(stage = "准备中…"),
            active = true
        )
        return id
    }

    fun update(jobId: Int, progress: ModpackInstallProgress) {
        val cur = _job.value ?: return
        if (cur.id != jobId || !cur.active) return
        _job.value = cur.copy(progress = progress)
    }

    fun succeed(jobId: Int, detail: String = "") {
        val cur = _job.value ?: return
        if (cur.id != jobId) return
        _job.value = cur.copy(
            progress = ModpackInstallProgress(stage = "安装完成", detail = detail),
            active = false
        )
        // Clear shortly so the global bar can hide; Toast already notifies the user.
        _job.value = null
    }

    fun fail(jobId: Int, message: String) {
        val cur = _job.value ?: return
        if (cur.id != jobId) return
        _job.value = cur.copy(
            progress = ModpackInstallProgress(stage = "安装失败", detail = message),
            active = false
        )
        _job.value = null
    }

    fun snapshots(): Flow<InstallProgressHub.Snapshot?> = job.map { snap ->
        if (snap == null || !snap.active) return@map null
        val p = snap.progress
        val fraction = when {
            p.total > 0 -> (p.current.toFloat() / p.total.toFloat()).coerceIn(0f, 1f)
            p.bytesTotal > 0L -> (p.bytesDownloaded.toFloat() / p.bytesTotal.toFloat()).coerceIn(0f, 1f)
            else -> -1f
        }
        val message = buildString {
            append(p.stage)
            if (p.total > 0) append(" (${p.current}/${p.total})")
            if (p.detail.isNotBlank()) {
                append(" · ")
                append(p.detail.take(48))
            }
        }
        InstallProgressHub.Snapshot(
            title = snap.title,
            message = message,
            fraction = fraction,
            active = true
        )
    }
}
