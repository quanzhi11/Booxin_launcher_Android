package com.booxin.launcher.ui.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.launch.GameCrashKind
import com.booxin.launcher.core.launch.GameCrashReport
import com.booxin.launcher.core.launch.GameCrashSuspectMod
import com.booxin.launcher.core.version.VersionModsManager
import com.booxin.launcher.databinding.DialogGameCrashBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 游戏意外退出后的处理弹窗：原因 → 建议 → 可转发日志。 */
object GameCrashDialog {

    fun showIfNeeded(activity: AppCompatActivity, report: GameCrashReport) {
        val binding = DialogGameCrashBinding.inflate(LayoutInflater.from(activity))
        binding.textCrashSummary.text = report.summary
        binding.textCrashHint.text = report.suggestion.ifBlank {
            hintForKind(activity, report.kind)
        }
        binding.textCrashDetail.text = report.detail

        val shareBody = report.buildShareText()
        binding.btnCrashCopyLog.setOnClickListener { copyLog(activity, shareBody) }
        binding.btnCrashShareLog.setOnClickListener { shareLog(activity, shareBody) }

        val checkBoxes = ArrayList<CheckBox>()
        val canPickMods = report.suspectMods.isNotEmpty() &&
            report.kind != GameCrashKind.MISSING_DEPENDENCY
        if (canPickMods) {
            binding.textCrashModsTitle.isVisible = true
            binding.layoutCrashMods.isVisible = true
            for (mod in report.suspectMods) {
                val row = CheckBox(activity).apply {
                    text = buildModLabel(mod)
                    isChecked = true
                    tag = mod
                }
                checkBoxes += row
                binding.layoutCrashMods.addView(row)
            }
        }

        val hasMissing = report.missingMods.isNotEmpty()
        if (hasMissing) {
            binding.textCrashDepsTitle.isVisible = true
            binding.layoutCrashDeps.isVisible = true
            for (dep in report.missingMods) {
                val line = TextView(activity).apply {
                    text = "· ${dep.displayHint}"
                    setPadding(0, 4, 0, 4)
                }
                binding.layoutCrashDeps.addView(line)
            }
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.crash_dialog_title)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.crash_dialog_share_log) { _, _ ->
                shareLog(activity, shareBody)
            }

        if (hasMissing) {
            builder.setPositiveButton(R.string.crash_dialog_download_deps) { _, _ ->
                downloadMissingDeps(activity, binding, report)
            }
        } else if (canPickMods) {
            builder.setPositiveButton(R.string.crash_dialog_disable_retry) { _, _ ->
                disableSelectedAndRetry(activity, binding, report, checkBoxes)
            }
        } else {
            builder.setPositiveButton(R.string.crash_dialog_retry) { _, _ ->
                retryLaunch(activity, report.versionId)
            }
        }

        val dialog = builder.create()
        dialog.show()
        // Cap height so NestedScrollView can actually scroll on small screens.
        dialog.window?.let { window ->
            val maxH = (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
            val w = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
            window.setLayout(w, maxH)
        }
    }

    private fun copyLog(context: Context, log: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("booxin_crash_log", log))
        Toast.makeText(context, R.string.crash_dialog_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLog(context: Context, log: String) {
        runCatching {
            val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "booxin-game-crash-$stamp.txt")
            file.writeText(log)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_dialog_share_subject))
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, log.take(8_000))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.crash_dialog_share_log))
            )
        }.onFailure {
            copyLog(context, log)
        }
    }

    private fun buildModLabel(mod: GameCrashSuspectMod): String {
        val reason = mod.reason?.let { "\n$it" }.orEmpty()
        return mod.displayName + reason
    }

    private fun hintForKind(activity: AppCompatActivity, kind: GameCrashKind): String =
        when (kind) {
            GameCrashKind.VRAM_OOM -> activity.getString(R.string.crash_dialog_vram_hint)
            GameCrashKind.NATIVE_INCOMPATIBLE ->
                activity.getString(R.string.crash_dialog_native_hint)
            GameCrashKind.MIXIN_ERROR -> activity.getString(R.string.crash_dialog_mixin_hint)
            GameCrashKind.JVM_CRASH -> activity.getString(R.string.crash_dialog_jvm_hint)
            GameCrashKind.PROCESS_DIED -> activity.getString(R.string.crash_dialog_process_hint)
            GameCrashKind.POJAV_SODIUM -> activity.getString(R.string.crash_dialog_pojav_hint)
            else -> activity.getString(R.string.crash_dialog_generic_hint)
        }

    private fun disableSelectedAndRetry(
        activity: AppCompatActivity,
        binding: DialogGameCrashBinding,
        report: GameCrashReport,
        checkBoxes: List<CheckBox>
    ) {
        val selected = checkBoxes.filter { it.isChecked }.mapNotNull { it.tag as? GameCrashSuspectMod }
        if (selected.isEmpty()) {
            Toast.makeText(activity, R.string.crash_dialog_pick_mod, Toast.LENGTH_SHORT).show()
            return
        }
        activity.lifecycleScope.launch {
            setBusy(binding, activity.getString(R.string.crash_dialog_disabling))
            val mods = VersionModsManager.list(report.versionId)
            var disabled = 0
            for (pick in selected) {
                val mod = mods.firstOrNull { it.file.name == pick.fileName } ?: continue
                if (!mod.enabled) continue
                if (VersionModsManager.toggle(mod).isSuccess) disabled++
            }
            setBusy(binding, activity.getString(R.string.crash_dialog_disabled_count, disabled))
            retryLaunch(activity, report.versionId)
        }
    }

    private fun downloadMissingDeps(
        activity: AppCompatActivity,
        binding: DialogGameCrashBinding,
        report: GameCrashReport
    ) {
        activity.lifecycleScope.launch {
            setBusy(binding, activity.getString(R.string.crash_dialog_downloading))
            val ids = report.missingMods.map { it.modId }
            val result = AppContainer.communityRepository.installMissingMods(
                targetVersionId = report.versionId,
                modIds = ids
            ) { progress ->
                activity.runOnUiThread {
                    binding.textCrashActionStatus.text = "${progress.stage}: ${progress.detail}"
                }
            }
            if (result.isSuccess) {
                val names = result.getOrNull().orEmpty().joinToString()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.crash_dialog_download_ok, names),
                    Toast.LENGTH_LONG
                ).show()
                retryLaunch(activity, report.versionId)
            } else {
                Toast.makeText(
                    activity,
                    activity.getString(
                        R.string.crash_dialog_download_fail,
                        result.exceptionOrNull()?.message ?: "unknown"
                    ),
                    Toast.LENGTH_LONG
                ).show()
                binding.progressCrashAction.isVisible = false
            }
        }
    }

    private fun retryLaunch(activity: AppCompatActivity, versionId: String) {
        activity.lifecycleScope.launch {
            val account = AppContainer.repository.selectedAccount()
            if (account == null) {
                Toast.makeText(activity, R.string.crash_dialog_no_account, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val result = AppContainer.gameRuntime.launch(activity, versionId, account)
            if (result.isFailure) {
                val err = result.exceptionOrNull()
                if (err is kotlinx.coroutines.CancellationException) return@launch
                Toast.makeText(
                    activity,
                    activity.getString(R.string.home_launch_failed, err?.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setBusy(binding: DialogGameCrashBinding, message: String) {
        binding.progressCrashAction.isVisible = true
        binding.textCrashActionStatus.isVisible = true
        binding.textCrashActionStatus.text = message
    }
}
