package com.booxin.launcher.ui.launch

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.booxin.launcher.R
import com.booxin.launcher.core.multiplayer.LocalMinecraftPortScanner
import com.booxin.launcher.core.multiplayer.RoomHostDependencyInfo
import com.booxin.launcher.core.multiplayer.RoomHostDependencyService
import com.booxin.launcher.databinding.DialogIngameMultiplayerBinding
import com.booxin.launcher.databinding.DialogIngameRoomCreateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Create-room form hosted inside [InGameMultiplayerPanel] (no nested dialog — :game safe).
 */
class InGameRoomCreateForm(
    private val activity: AppCompatActivity,
    private val panel: DialogIngameMultiplayerBinding,
    private val form: DialogIngameRoomCreateBinding,
    private val versionId: String?,
    private val suggestedRoomName: String,
    private val onConfirm: (InGameRoomCreateDialog.Settings) -> Unit,
    private val onCancel: () -> Unit
) {
    private var scannedDeps = RoomHostDependencyInfo()
    private var scanJob: Job? = null
    private var portJob: Job? = null
    private var portManualOverride = false
    private var readyToCreate = false

    fun start() {
        form.inputRoomName.setText(suggestedRoomName)
        if (form.inputPort.text.isNullOrBlank()) {
            form.inputPort.setText("25565")
        }
        panel.buttonConfirmCreate.isEnabled = false
        panel.buttonBackCreate.setOnClickListener {
            dispose()
            onCancel()
        }
        form.buttonRescanPort.setOnClickListener {
            portManualOverride = false
            runPortScan(force = true)
        }
        form.inputPort.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) portManualOverride = true
        }
        panel.buttonConfirmCreate.setOnClickListener { submit() }

        runPortScan()
        startDepsScan()
    }

    fun dispose() {
        scanJob?.cancel()
        portJob?.cancel()
        scanJob = null
        portJob = null
    }

    private fun runPortScan(force: Boolean = false) {
        if (!force && portManualOverride) return
        portJob?.cancel()
        form.textPortScanStatus.setText(R.string.ingame_mp_port_scanning)
        form.buttonRescanPort.isEnabled = false
        portJob = activity.lifecycleScope.launch {
            val worlds = withContext(Dispatchers.IO) {
                runCatching {
                    LocalMinecraftPortScanner.scan(activity.applicationContext)
                }.getOrDefault(emptyList())
            }
            if (panel.panelCreate.isVisible.not()) return@launch
            form.buttonRescanPort.isEnabled = true
            if (!force && portManualOverride) return@launch
            val best = worlds.firstOrNull()
            if (best != null) {
                form.inputPort.setText(best.port.toString())
                form.textPortScanStatus.text = activity.getString(
                    R.string.ingame_mp_port_found,
                    best.port,
                    best.name
                )
            } else {
                if (form.inputPort.text.isNullOrBlank()) {
                    form.inputPort.setText("25565")
                }
                form.textPortScanStatus.setText(R.string.ingame_mp_port_not_found)
            }
        }
    }

    private fun startDepsScan() {
        val scanId = versionId?.trim().orEmpty()
        if (scanId.isEmpty()) {
            form.textDepsGameVersion.text = "未选择游戏实例"
            form.textDepsStatus.text = "请先从启动器选择要联机的版本后再开房。"
            form.textDepsVanilla.isVisible = true
            readyToCreate = true
            panel.buttonConfirmCreate.isEnabled = true
            return
        }
        form.textDepsGameVersion.setText(R.string.ingame_mp_deps_scanning)
        form.textDepsStatus.setText(R.string.ingame_mp_deps_scanning)
        scanJob = activity.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching {
                    RoomHostDependencyService().scanInstance(
                        versionId = scanId,
                        onProgress = { status ->
                            activity.runOnUiThread {
                                if (panel.panelCreate.isVisible) {
                                    form.textDepsStatus.text = status
                                }
                            }
                        }
                    )
                }.getOrElse {
                    Log.w(TAG, "deps scan failed: ${it.message}")
                    RoomHostDependencyInfo()
                }
            }
            if (panel.panelCreate.isVisible.not()) return@launch
            scannedDeps = info
            applyDepsUi(info)
            readyToCreate = true
            panel.buttonConfirmCreate.isEnabled = true
        }
    }

    private fun submit() {
        if (!readyToCreate) {
            Toast.makeText(activity, R.string.ingame_mp_working, Toast.LENGTH_SHORT).show()
            return
        }
        val port = form.inputPort.text?.toString()?.trim()?.toIntOrNull()
        if (port == null || port !in 100..65535) {
            Toast.makeText(activity, R.string.ingame_mp_invalid_port, Toast.LENGTH_SHORT).show()
            return
        }
        val modpack = form.inputModpackUrl.text?.toString()?.trim().orEmpty()
        val settings = InGameRoomCreateDialog.Settings(
            roomName = form.inputRoomName.text?.toString()?.trim().orEmpty(),
            roomRemark = form.inputRoomRemark.text?.toString()?.trim().orEmpty(),
            modpackUrl = modpack.takeIf { it.isNotEmpty() },
            isPublic = form.radioPublic.isChecked,
            minecraftPort = port,
            dependencyInfo = scannedDeps
        )
        dispose()
        onConfirm(settings)
    }

    private fun applyDepsUi(info: RoomHostDependencyInfo) {
        val version = info.gameVersion.ifBlank { "未知版本" }
        val loader = info.loader?.ifBlank { null } ?: "原版/未知加载器"
        form.textDepsGameVersion.text = "游戏版本：$version（$loader）"
        val linked = info.mods.filter { !it.pageUrl.isNullOrBlank() || it.hasDownloadSource }
        val unlinked = info.mods.size - linked.size
        when {
            info.mods.isEmpty() -> {
                form.textDepsStatus.text = info.summaryText
                form.textDepsMods.isVisible = false
                form.textDepsVanilla.isVisible = true
            }
            else -> {
                form.textDepsStatus.text = buildString {
                    append(info.summaryText)
                    if (unlinked > 0) {
                        append('\n')
                        append(activity.getString(R.string.ingame_mp_deps_unlinked_hint, unlinked))
                    }
                }
                form.textDepsVanilla.isVisible = false
                form.textDepsMods.isVisible = true
                form.textDepsMods.text = linked
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

    companion object {
        private const val TAG = "InGameRoomCreate"
    }
}

/** Kept for Settings type compatibility. */
object InGameRoomCreateDialog {
    data class Settings(
        val roomName: String,
        val roomRemark: String,
        val modpackUrl: String?,
        val isPublic: Boolean,
        val minecraftPort: Int,
        val dependencyInfo: RoomHostDependencyInfo = RoomHostDependencyInfo()
    )
}
