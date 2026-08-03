package com.booxin.launcher.ui.update

import android.app.Activity
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.booxin.launcher.R
import com.booxin.launcher.core.update.LauncherUpdateChecker
import com.booxin.launcher.core.update.LauncherUpdateInfo
import com.booxin.launcher.core.update.LauncherUpdateInstaller
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LauncherUpdateUi {

    /**
     * @param silentWhenLatest if true, do not toast when already up to date (startup check).
     */
    fun check(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        silentWhenLatest: Boolean = true
    ) {
        lifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { LauncherUpdateChecker.fetch() }
            if (activity.isFinishing) return@launch
            result.fold(
                onSuccess = { info ->
                    val localCode = LauncherUpdateChecker.localVersionCode()
                    val localName = LauncherUpdateChecker.localVersionName()
                    val force = info.forceUpdate || info.isBelowMinimum(localCode)
                    val newer = info.isNewerThan(localCode, localName)
                    when {
                        newer || force ->
                            showUpdateDialog(activity, lifecycleOwner, info, force)
                        !silentWhenLatest ->
                            Toast.makeText(
                                activity,
                                activity.getString(
                                    R.string.update_already_latest,
                                    localName,
                                    info.latestVersion
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                    }
                },
                onFailure = { err ->
                    if (!silentWhenLatest) {
                        Toast.makeText(
                            activity,
                            activity.getString(
                                R.string.update_check_failed,
                                err.message ?: "unknown"
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }
    }

    private fun showUpdateDialog(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: LauncherUpdateInfo,
        force: Boolean
    ) {
        val notes = info.releaseNotes.ifBlank {
            activity.getString(R.string.update_no_notes)
        }
        val message = activity.getString(
            R.string.update_dialog_message,
            info.latestVersion,
            LauncherUpdateChecker.localVersionName(),
            notes
        )
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_dialog_title)
            .setMessage(message)
            .setCancelable(!force)
            .setPositiveButton(R.string.update_download) { _, _ ->
                startDownload(activity, lifecycleOwner, info)
            }
        if (!force) {
            builder.setNegativeButton(R.string.update_later, null)
        }
        builder.show()
    }

    private fun startDownload(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: LauncherUpdateInfo
    ) {
        if (!info.hasApk()) {
            Toast.makeText(activity, R.string.update_apk_missing, Toast.LENGTH_LONG).show()
            return
        }
        if (!LauncherUpdateInstaller.canRequestPackageInstalls(activity)) {
            Toast.makeText(activity, R.string.update_need_install_permission, Toast.LENGTH_LONG).show()
            LauncherUpdateInstaller.openUnknownSourcesSettings(activity)
            return
        }

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null)
        val progress = view.findViewById<ProgressBar>(R.id.progressUpdate)
        val text = view.findViewById<TextView>(R.id.textUpdateProgress)
        progress.isIndeterminate = true
        text.setText(R.string.update_downloading)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_downloading_title)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleOwner.lifecycleScope.launch {
            val result = LauncherUpdateInstaller.downloadApk(activity, info) { downloaded, total ->
                activity.runOnUiThread {
                    if (total > 0) {
                        progress.isIndeterminate = false
                        progress.max = 100
                        progress.progress = ((downloaded * 100) / total).toInt()
                        text.text = activity.getString(
                            R.string.update_progress_fmt,
                            downloaded / 1024 / 1024,
                            total / 1024 / 1024
                        )
                    } else {
                        progress.isIndeterminate = true
                        text.text = activity.getString(
                            R.string.update_progress_bytes,
                            downloaded / 1024
                        )
                    }
                }
            }
            if (activity.isFinishing) return@launch
            dialog.dismiss()
            result.fold(
                onSuccess = { apk ->
                    Toast.makeText(activity, R.string.update_install_prompt, Toast.LENGTH_SHORT).show()
                    runCatching { LauncherUpdateInstaller.installApk(activity, apk) }
                        .onFailure {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.update_install_failed, it.message),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                },
                onFailure = {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_download_failed, it.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }
}
