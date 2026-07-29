package com.booxin.launcher.ui.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.booxin.launcher.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MicrosoftAuthErrorDialog {

    fun show(context: Context, summary: String, log: String) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.accounts_ms_error_title)
            .setMessage(summary)
            .setPositiveButton(R.string.accounts_ms_copy_log) { _, _ ->
                copyLog(context, log)
            }
            .setNeutralButton(R.string.accounts_ms_share_log) { _, _ ->
                shareLog(context, log)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyLog(context: Context, log: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("ms_auth_log", log))
        Toast.makeText(context, R.string.accounts_ms_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLog(context: Context, log: String) {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "booxin-ms-auth-$stamp.txt")
        file.writeText(log)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Booxin Microsoft Auth Log")
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Booxin 微软登录日志，请发给开发者。")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.accounts_ms_share_log))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
