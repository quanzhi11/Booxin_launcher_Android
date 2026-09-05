package com.booxin.launcher.ui.launch

import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.booxin.launcher.R
import com.booxin.launcher.core.multiplayer.RoomHostDependencyInfo
import com.booxin.launcher.core.multiplayer.RoomHostDependencyService
import com.booxin.launcher.databinding.DialogIngameRoomCreateBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object InGameRoomCreateDialog {

    data class Settings(
        val roomName: String,
        val roomRemark: String,
        val modpackUrl: String?,
        val isPublic: Boolean,
        val minecraftPort: Int,
        val dependencyInfo: RoomHostDependencyInfo = RoomHostDependencyInfo()
    )

    fun show(
        activity: AppCompatActivity,
        suggestedRoomName: String,
        versionId: String? = null,
        onConfirm: (Settings) -> Unit
    ) {
        val binding = DialogIngameRoomCreateBinding.inflate(LayoutInflater.from(activity))
        binding.inputRoomName.setText(suggestedRoomName)
        var scannedDeps = RoomHostDependencyInfo()
        var scanJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ingame_mp_create_settings_title)
            .setView(binding.root)
            .setPositiveButton(R.string.ingame_mp_create_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = false

            val scanId = versionId?.trim().orEmpty()
            if (scanId.isEmpty()) {
                binding.textDepsGameVersion.text = "未选择游戏实例"
                binding.textDepsStatus.text = "请先从启动器选择要联机的版本后再开房。"
                binding.textDepsVanilla.isVisible = true
                positive.isEnabled = true
            } else {
                binding.textDepsGameVersion.setText(R.string.ingame_mp_deps_scanning)
                binding.textDepsStatus.setText(R.string.ingame_mp_deps_scanning)
                scanJob = activity.lifecycleScope.launch {
                    val info = withContext(Dispatchers.IO) {
                        runCatching {
                            RoomHostDependencyService().scanInstance(
                                versionId = scanId,
                                onProgress = { status ->
                                    activity.runOnUiThread {
                                        if (dialog.isShowing) {
                                            binding.textDepsStatus.text = status
                                        }
                                    }
                                }
                            )
                        }.getOrElse {
                            RoomHostDependencyInfo()
                        }
                    }
                    if (!dialog.isShowing) return@launch
                    scannedDeps = info
                    applyDepsUi(activity, binding, info)
                    positive.isEnabled = true
                }
            }

            positive.setOnClickListener {
                val port = binding.inputPort.text?.toString()?.trim()?.toIntOrNull()
                if (port == null || port !in 100..65535) {
                    Toast.makeText(activity, R.string.ingame_mp_invalid_port, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val modpack = binding.inputModpackUrl.text?.toString()?.trim().orEmpty()
                onConfirm(
                    Settings(
                        roomName = binding.inputRoomName.text?.toString()?.trim().orEmpty(),
                        roomRemark = binding.inputRoomRemark.text?.toString()?.trim().orEmpty(),
                        modpackUrl = modpack.takeIf { it.isNotEmpty() },
                        isPublic = binding.radioPublic.isChecked,
                        minecraftPort = port,
                        dependencyInfo = scannedDeps
                    )
                )
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { scanJob?.cancel() }
        dialog.show()
    }

    private fun applyDepsUi(
        activity: AppCompatActivity,
        binding: DialogIngameRoomCreateBinding,
        info: RoomHostDependencyInfo
    ) {
        val version = info.gameVersion.ifBlank { "未知版本" }
        val loader = info.loader?.ifBlank { null } ?: "原版/未知加载器"
        binding.textDepsGameVersion.text = "游戏版本：$version（$loader）"
        val linked = info.mods.filter { !it.pageUrl.isNullOrBlank() || it.hasDownloadSource }
        val unlinked = info.mods.size - linked.size
        when {
            info.mods.isEmpty() -> {
                binding.textDepsStatus.text = info.summaryText
                binding.textDepsMods.isVisible = false
                binding.textDepsVanilla.isVisible = true
            }
            else -> {
                binding.textDepsStatus.text = buildString {
                    append(info.summaryText)
                    if (unlinked > 0) {
                        append('\n')
                        append(activity.getString(R.string.ingame_mp_deps_unlinked_hint, unlinked))
                    }
                }
                binding.textDepsVanilla.isVisible = false
                binding.textDepsMods.isVisible = true
                binding.textDepsMods.text = linked
                    .take(24)
                    .joinToString("\n") { mod ->
                        buildString {
                            append("· ")
                            append(mod.name.ifBlank { mod.fileName ?: "模组" })
                            mod.pageUrl?.takeIf { it.isNotBlank() }?.let {
                                append('\n')
                                append("  ")
                                append(it)
                            }
                        }
                    }.ifBlank { info.summaryText }
            }
        }
    }
}
