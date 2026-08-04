package com.booxin.launcher.ui.launch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.booxin.launcher.R
import com.booxin.launcher.core.multiplayer.InGameLobbyController
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * In-game floating-menu multiplayer panel: create / join / members / room code.
 */
class InGameMultiplayerPanel(
    private val activity: AppCompatActivity,
    private val playerNameProvider: () -> String = { "Player" }
) {
    private val lobby = InGameLobbyController(activity.applicationContext)
    private var workJob: Job? = null
    private var dialog: AlertDialog? = null

    fun show() {
        if (lobby.loadSession() == null) {
            Toast.makeText(activity, R.string.ingame_mp_no_session, Toast.LENGTH_LONG).show()
            return
        }
        // Compact padding for the floating dialog.
        val pad = (12 * activity.resources.displayMetrics.density).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val statusView = TextView(activity).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        val membersView = TextView(activity).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        val progress = ProgressBar(activity).apply {
            isIndeterminate = true
            isVisible = false
        }
        val hint = TextView(activity).apply {
            text = activity.getString(R.string.ingame_mp_hint)
            textSize = 12f
        }

        lateinit var btnCreate: MaterialButton
        lateinit var btnJoin: MaterialButton
        lateinit var btnCopy: MaterialButton
        lateinit var btnRefresh: MaterialButton
        lateinit var btnLeave: MaterialButton

        fun refreshUi() {
            val lobbyInfo = lobby.lobby.value
            val status = lobby.status.value
            val members = lobby.members.value
            val host = lobby.isHost.value
            val connect = lobby.directConnect.value
            val inRoom = lobbyInfo != null
            statusView.text = buildString {
                if (lobbyInfo == null) {
                    append(activity.getString(R.string.ingame_mp_idle))
                } else {
                    append(activity.getString(R.string.ingame_mp_room_line, lobbyInfo.roomCode))
                    append('\n')
                    append(
                        if (host) activity.getString(R.string.ingame_mp_role_host)
                        else activity.getString(R.string.ingame_mp_role_guest)
                    )
                    if (!connect.isNullOrBlank()) {
                        append('\n')
                        append("直连：$connect")
                    }
                }
                if (!status.isNullOrBlank()) {
                    append("\n\n")
                    append(status)
                }
            }
            membersView.text = buildString {
                append(activity.getString(R.string.ingame_mp_members_title))
                append('\n')
                if (members.isEmpty()) {
                    append(activity.getString(R.string.ingame_mp_members_empty))
                } else {
                    val shown = members.take(3)
                    shown.forEach { m ->
                        val role = when {
                            m.isHost -> "房主"
                            m.kind.equals("GUEST", true) -> "访客"
                            else -> m.kind ?: "玩家"
                        }
                        append("· ${m.name}（$role）\n")
                    }
                    if (members.size > shown.size) {
                        append("… +${members.size - shown.size} 更多\n")
                    }
                }
            }
            hint.isVisible = !inRoom
            membersView.isVisible = inRoom
            btnCreate.isVisible = !inRoom
            btnJoin.isVisible = !inRoom
            btnCopy.isVisible = inRoom
            btnRefresh.isVisible = inRoom
            btnLeave.isVisible = inRoom
        }

        fun actionButton(label: String, onClick: () -> Unit): MaterialButton =
            MaterialButton(activity).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = pad / 3 }
                setOnClickListener { onClick() }
            }

        btnCreate = actionButton(activity.getString(R.string.ingame_mp_create)) {
            promptCreate { port, isPublic ->
                runWork(progress) {
                    lobby.createRoom(
                        minecraftPort = port,
                        playerNameHint = playerNameProvider(),
                        isPublic = isPublic
                    ).onFailure {
                        Toast.makeText(activity, it.message ?: "创建失败", Toast.LENGTH_LONG).show()
                    }
                    refreshUi()
                }
            }
        }
        btnJoin = actionButton(activity.getString(R.string.ingame_mp_join)) {
            promptJoin { code ->
                runWork(progress) {
                    lobby.joinRoom(code)
                        .onFailure {
                            Toast.makeText(activity, it.message ?: "加入失败", Toast.LENGTH_LONG).show()
                        }
                    refreshUi()
                }
            }
        }
        btnCopy = actionButton(activity.getString(R.string.ingame_mp_copy_code)) {
            val code = lobby.lobby.value?.roomCode
            if (code.isNullOrBlank()) {
                Toast.makeText(activity, R.string.ingame_mp_idle, Toast.LENGTH_SHORT).show()
            } else {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("room", code))
                Toast.makeText(activity, R.string.ingame_mp_copied, Toast.LENGTH_SHORT).show()
            }
        }
        btnRefresh = actionButton(activity.getString(R.string.ingame_mp_refresh)) {
            lobby.refreshMembersFromHost()
            refreshUi()
            Toast.makeText(activity, R.string.ingame_mp_working, Toast.LENGTH_SHORT).show()
        }
        btnLeave = actionButton(activity.getString(R.string.ingame_mp_leave)) {
            runWork(progress) {
                lobby.leave()
                refreshUi()
            }
        }

        root.addView(hint)
        root.addView(statusView)
        root.addView(membersView)
        root.addView(progress)
        root.addView(btnCreate)
        root.addView(btnJoin)
        root.addView(btnCopy)
        root.addView(btnRefresh)
        root.addView(btnLeave)

        val scroll = ScrollView(activity).apply { addView(root) }
        refreshUi()
        dialog?.dismiss()
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ingame_mp_title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { it.show() }
    }

    fun dispose() {
        workJob?.cancel()
        dialog?.dismiss()
        dialog = null
    }

    private fun runWork(progress: ProgressBar, block: suspend () -> Unit) {
        workJob?.cancel()
        progress.isVisible = true
        workJob = activity.lifecycleScope.launch {
            try {
                block()
            } finally {
                progress.isVisible = false
            }
        }
    }

    private fun promptCreate(onConfirm: (port: Int, isPublic: Boolean) -> Unit) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = activity.getString(R.string.ingame_mp_port_hint)
            setText("25565")
        }
        val publicCheck = CheckBox(activity).apply {
            text = activity.getString(R.string.ingame_mp_public)
            isChecked = true
        }
        box.addView(TextView(activity).apply { text = activity.getString(R.string.ingame_mp_port_title) })
        box.addView(input)
        box.addView(publicCheck)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ingame_mp_create)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val port = input.text?.toString()?.trim()?.toIntOrNull()
                if (port == null || port !in 100..65535) {
                    Toast.makeText(activity, R.string.ingame_mp_invalid_port, Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(port, publicCheck.isChecked)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptJoin(onConfirm: (code: String) -> Unit) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = activity.getString(R.string.ingame_mp_code_hint)
        }
        box.addView(TextView(activity).apply { text = activity.getString(R.string.ingame_mp_code_title) })
        box.addView(input)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ingame_mp_join)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.length < 10) {
                    Toast.makeText(activity, R.string.ingame_mp_invalid_code, Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(code)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
