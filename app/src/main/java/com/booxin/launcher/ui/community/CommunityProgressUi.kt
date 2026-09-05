package com.booxin.launcher.ui.community

import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.booxin.launcher.data.model.ModpackInstallProgress

/** Community download / load progress — percent bar instead of duplicate spinners. */
object CommunityProgressUi {

    fun show(
        panel: View,
        progressBar: ProgressBar,
        textView: TextView,
        message: String,
        bytesDownloaded: Long = -1L,
        bytesTotal: Long = -1L,
        showBar: Boolean = true
    ) {
        panel.isVisible = true
        if (!showBar || bytesTotal <= 0L) {
            progressBar.isVisible = false
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            textView.text = message
            return
        }
        progressBar.isVisible = true
        progressBar.isIndeterminate = false
        val pct = ((bytesDownloaded.coerceAtLeast(0L) * 100L) / bytesTotal)
            .toInt()
            .coerceIn(0, 100)
        progressBar.progress = pct
        textView.text = "$message · $pct%"
    }

    fun show(panel: View, progressBar: ProgressBar, textView: TextView, progress: ModpackInstallProgress) {
        val message = buildString {
            append(progress.stage)
            if (progress.total > 0) append(" (${progress.current}/${progress.total})")
            if (progress.detail.isNotBlank()) {
                append(" — ")
                append(progress.detail)
            }
        }
        show(
            panel = panel,
            progressBar = progressBar,
            textView = textView,
            message = message,
            bytesDownloaded = progress.bytesDownloaded,
            bytesTotal = progress.bytesTotal,
            showBar = progress.bytesTotal > 0L
        )
    }

    fun hide(panel: View, progressBar: ProgressBar) {
        panel.isVisible = false
        progressBar.isIndeterminate = false
        progressBar.progress = 0
    }
}
