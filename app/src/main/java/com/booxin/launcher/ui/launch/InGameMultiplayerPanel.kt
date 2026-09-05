package com.booxin.launcher.ui.launch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.multiplayer.InGameLobbyController
import com.booxin.launcher.core.multiplayer.PublicRoom
import com.booxin.launcher.core.multiplayer.RoomDependencySnapshot
import com.booxin.launcher.core.multiplayer.RoomHostDependencyInstaller
import com.booxin.launcher.core.multiplayer.RoomSessionTracker
import com.booxin.launcher.databinding.DialogIngameMultiplayerBinding
import com.booxin.launcher.ui.multiplayer.MultiplayerListItem
import com.booxin.launcher.ui.multiplayer.MultiplayerUserAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * In-game floating-menu multiplayer panel with browse / room detail / in-room states.
 */
class InGameMultiplayerPanel(
    private val activity: AppCompatActivity,
    private val playerNameProvider: () -> String = { "Player" },
    private val versionIdProvider: () -> String = { "" }
) {
    private val lobby = InGameLobbyController(activity.applicationContext)
    private var workJob: Job? = null
    private var roomsJob: Job? = null
    private var depsJob: Job? = null
    private var dialog: AlertDialog? = null
    private var binding: DialogIngameMultiplayerBinding? = null
    private var selectedPublicRoom: PublicRoom? = null
    private var cachedHostDeps: RoomDependencySnapshot? = null
    private var cachedHostDepsRoomCode: String? = null
    private var hostDepsBusy = false

    private val roomsAdapter = MultiplayerUserAdapter(
        onItemClick = { item ->
            val room = item.payload as? PublicRoom ?: return@MultiplayerUserAdapter
            showPublicRoomDetail(room)
        }
    )

    fun show() {
        if (lobby.loadSession() == null) {
            Toast.makeText(activity, R.string.ingame_mp_no_session, Toast.LENGTH_LONG).show()
            return
        }
        val inflater = LayoutInflater.from(activity)
        val panelBinding = DialogIngameMultiplayerBinding.inflate(inflater)
        binding = panelBinding
        panelBinding.recyclerPublicRooms.layoutManager = LinearLayoutManager(activity)
        panelBinding.recyclerPublicRooms.adapter = roomsAdapter

        panelBinding.buttonCreate.setOnClickListener { promptCreate() }
        panelBinding.buttonJoinCode.setOnClickListener {
            joinRoom(panelBinding.inputRoomCode.text?.toString())
        }
        panelBinding.buttonRefreshRooms.setOnClickListener { refreshPublicRooms() }
        panelBinding.buttonLeave.setOnClickListener {
            runWork {
                lobby.leave()
                selectedPublicRoom = null
                clearHostDepsCache()
                renderPanel()
            }
        }
        panelBinding.buttonDownloadHostDeps.setOnClickListener { downloadHostDependencies() }
        panelBinding.buttonCopyCode.setOnClickListener { copyRoomCode() }
        panelBinding.buttonRefreshMembers.setOnClickListener {
            lobby.refreshMembersFromHost()
            renderPanel()
            Toast.makeText(activity, R.string.ingame_mp_working, Toast.LENGTH_SHORT).show()
        }
        panelBinding.buttonBack.setOnClickListener {
            selectedPublicRoom = null
            renderPanel()
        }
        panelBinding.buttonJoinDetail.setOnClickListener {
            joinRoom(selectedPublicRoom?.roomCode)
        }

        renderPanel()
        refreshPublicRooms()

        dialog?.dismiss()
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.72f).toInt()
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ingame_mp_title)
            .setView(panelBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dlg ->
                dlg.setOnShowListener {
                    dlg.window?.setLayout(
                        (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                        maxHeight
                    )
                }
                dlg.show()
            }
    }

    /** Called from LaunchActivity before killing `:game` — unpublish host / leave guest. */
    suspend fun leaveForExit() {
        runCatching { lobby.leave() }
    }

    fun dispose() {
        workJob?.cancel()
        roomsJob?.cancel()
        depsJob?.cancel()
        dialog?.dismiss()
        dialog = null
        binding = null
        selectedPublicRoom = null
        clearHostDepsCache()
    }

    private fun clearHostDepsCache() {
        cachedHostDeps = null
        cachedHostDepsRoomCode = null
        hostDepsBusy = false
        depsJob?.cancel()
        depsJob = null
    }

    private fun renderPanel() {
        val b = binding ?: return
        val inRoom = lobby.lobby.value != null
        val detail = if (inRoom) null else selectedPublicRoom

        b.panelBrowse.isVisible = !inRoom && detail == null
        b.panelInRoom.isVisible = inRoom
        b.panelPublicDetail.isVisible = !inRoom && detail != null

        val status = lobby.status.value.orEmpty()
        if (inRoom) {
            val lobbyInfo = lobby.lobby.value
            val host = lobby.isHost.value
            val connect = lobby.directConnect.value
            b.textStatusInRoom.text = buildString {
                if (lobbyInfo != null) {
                    append(activity.getString(R.string.ingame_mp_room_line, lobbyInfo.roomCode))
                    append('\n')
                    append(
                        if (host) activity.getString(R.string.ingame_mp_role_host)
                        else activity.getString(R.string.ingame_mp_role_guest)
                    )
                    if (!host) {
                        append('\n')
                        append(activity.getString(R.string.multiplayer_lan_join_hint))
                    }
                    if (!connect.isNullOrBlank()) {
                        append('\n')
                        append(activity.getString(R.string.multiplayer_direct_connect, connect))
                    }
                } else {
                    append(activity.getString(R.string.ingame_mp_idle))
                }
                if (status.isNotBlank()) {
                    append("\n\n")
                    append(status)
                }
            }
            val members = lobby.members.value
            b.textMembers.text = buildString {
                append(activity.getString(R.string.ingame_mp_members_title))
                append('\n')
                if (members.isEmpty()) {
                    append(activity.getString(R.string.ingame_mp_members_empty))
                } else {
                    members.take(6).forEach { m ->
                        val role = when {
                            m.isHost -> activity.getString(R.string.multiplayer_member_host)
                            m.kind.equals("GUEST", true) ->
                                activity.getString(R.string.multiplayer_member_guest)
                            else -> m.kind ?: "玩家"
                        }
                        append("· ${m.name}（$role）\n")
                    }
                    if (members.size > 6) {
                        append("… +${members.size - 6}\n")
                    }
                }
            }
            if (host) {
                b.buttonDownloadHostDeps.isVisible = false
                b.textHostDepsInfo.isVisible = false
            } else {
                refreshHostDepsUi(lobbyInfo?.roomCode)
            }
            return
        }

        clearHostDepsCache()
        b.buttonDownloadHostDeps.isVisible = false
        b.textHostDepsInfo.isVisible = false

        if (detail != null) {
            b.textDetailHost.text =
                detail.hostName.ifBlank { activity.getString(R.string.multiplayer_tab_rooms) }
            b.textDetailMotd.text = detail.motd.ifBlank {
                detail.remark.orEmpty().ifBlank { activity.getString(R.string.multiplayer_public_rooms) }
            }
            b.textDetailMeta.text = activity.getString(
                R.string.multiplayer_public_room_meta,
                detail.version?.ifBlank { "?" } ?: "?",
                detail.currentPlayers,
                detail.maxPlayers
            )
            b.textDetailCode.text =
                activity.getString(R.string.multiplayer_public_room_code, detail.roomCode)
            b.textStatusDetail.text = status
            return
        }

        b.textStatusBrowse.text = status.ifBlank { activity.getString(R.string.ingame_mp_idle) }
    }

    private fun refreshHostDepsUi(roomCode: String?) {
        val b = binding ?: return
        if (roomCode.isNullOrBlank()) {
            b.buttonDownloadHostDeps.isVisible = false
            b.textHostDepsInfo.isVisible = false
            return
        }
        val cached = cachedHostDeps
        if (cached != null && roomCode.equals(cachedHostDepsRoomCode, ignoreCase = true)) {
            applyHostDepsButton(cached)
            return
        }
        depsJob?.cancel()
        depsJob = activity.lifecycleScope.launch {
            val snapshot = runCatching {
                RoomHostDependencyInstaller.resolveSnapshot(roomCode)
            }.getOrDefault(RoomDependencySnapshot())
            if (binding == null) return@launch
            if (!roomCode.equals(lobby.lobby.value?.roomCode, ignoreCase = true)) return@launch
            cachedHostDepsRoomCode = roomCode
            cachedHostDeps = snapshot
            applyHostDepsButton(snapshot)
        }
    }

    private fun applyHostDepsButton(snapshot: RoomDependencySnapshot) {
        val b = binding ?: return
        val hasContent = RoomHostDependencyInstaller.hasDownloadableContent(snapshot)
        b.buttonDownloadHostDeps.isVisible = hasContent
        b.buttonDownloadHostDeps.isEnabled = hasContent && !hostDepsBusy
        b.textHostDepsInfo.isVisible = hasContent
        if (!hasContent) return
        val version = snapshot.gameVersion?.ifBlank { null } ?: "未知版本"
        val loader = snapshot.loader?.ifBlank { null } ?: "原版"
        val summary = buildString {
            append(version).append(" · ").append(loader)
            if (snapshot.mods.isNotEmpty()) append(" · ${snapshot.mods.size} 个模组")
            else if (!snapshot.modpackUrl.isNullOrBlank()) append(" · 整合包")
        }
        b.textHostDepsInfo.text = activity.getString(R.string.ingame_mp_host_deps_summary, summary)
        if (hostDepsBusy) {
            b.buttonDownloadHostDeps.setText(R.string.multiplayer_download_host_deps_busy)
            return
        }
        val exact = RoomHostDependencyInstaller.findExactMatchVersion(snapshot)
        b.buttonDownloadHostDeps.setText(
            if (exact != null) R.string.ingame_mp_download_host_deps_launch
            else R.string.ingame_mp_download_host_deps
        )
    }

    private fun downloadHostDependencies() {
        val roomCode = lobby.lobby.value?.roomCode ?: return
        if (lobby.isHost.value || hostDepsBusy) return
        runWork {
            val snapshot = cachedHostDeps?.takeIf {
                roomCode.equals(cachedHostDepsRoomCode, ignoreCase = true)
            } ?: RoomHostDependencyInstaller.resolveSnapshot(roomCode).also {
                cachedHostDepsRoomCode = roomCode
                cachedHostDeps = it
            }
            if (!RoomHostDependencyInstaller.hasDownloadableContent(snapshot)) {
                Toast.makeText(
                    activity,
                    R.string.multiplayer_download_host_deps_none,
                    Toast.LENGTH_SHORT
                ).show()
                applyHostDepsButton(snapshot)
                return@runWork
            }
            val exact = RoomHostDependencyInstaller.findExactMatchVersion(snapshot)
            if (exact != null) {
                AppContainer.repository.selectVersion(exact.id)
                RoomSessionTracker.track(exact.id)
                Toast.makeText(
                    activity,
                    activity.getString(
                        R.string.multiplayer_download_host_deps_ok,
                        "已匹配联机版本：${exact.id}（下次从启动器启动该版本）"
                    ),
                    Toast.LENGTH_LONG
                ).show()
                applyHostDepsButton(snapshot)
                return@runWork
            }
            val confirmed = suspendCancellableCoroutine { cont ->
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.ingame_mp_download_host_deps)
                    .setMessage(
                        activity.getString(
                            R.string.multiplayer_download_host_deps_confirm,
                            snapshot.gameVersion ?: "未知",
                            snapshot.loader ?: "原版",
                            snapshot.mods.size
                        )
                    )
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (cont.isActive) cont.resume(true)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        if (cont.isActive) cont.resume(false)
                    }
                    .setOnCancelListener {
                        if (cont.isActive) cont.resume(false)
                    }
                    .show()
            }
            if (!confirmed) return@runWork
            hostDepsBusy = true
            applyHostDepsButton(snapshot)
            val result = runCatching {
                RoomHostDependencyInstaller.downloadAndCreate(snapshot) { status ->
                    activity.runOnUiThread {
                        val ui = binding ?: return@runOnUiThread
                        ui.buttonDownloadHostDeps.text = status.take(28)
                        ui.textHostDepsInfo.text = status
                        ui.textHostDepsInfo.isVisible = true
                    }
                }
            }
            hostDepsBusy = false
            result.onSuccess { ok ->
                RoomSessionTracker.track(ok.versionId)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.multiplayer_download_host_deps_ok, ok.message),
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    activity,
                    activity.getString(
                        R.string.multiplayer_download_host_deps_failed,
                        err.message ?: "unknown"
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
            applyHostDepsButton(snapshot)
        }
    }

    private fun showPublicRoomDetail(room: PublicRoom) {
        if (lobby.lobby.value != null) return
        selectedPublicRoom = room
        renderPanel()
    }

    private fun refreshPublicRooms() {
        val b = binding ?: return
        roomsJob?.cancel()
        b.textRoomsEmpty.isVisible = false
        b.textRoomsEmpty.text = activity.getString(R.string.multiplayer_loading)
        roomsJob = activity.lifecycleScope.launch {
            b.progress.isVisible = true
            val result = lobby.listPublicRooms()
            val bound = binding ?: return@launch
            bound.progress.isVisible = false
            val rooms = result.getOrNull().orEmpty()
            roomsAdapter.submit(
                rooms.map { room ->
                    MultiplayerListItem(
                        id = room.id.ifBlank { room.roomCode },
                        name = "${room.hostName} · ${room.roomCode.take(8)}…",
                        meta = buildString {
                            append(room.motd.ifBlank { room.remark.orEmpty() }.ifBlank { "公开房间" })
                            append(" · ${room.currentPlayers}/${room.maxPlayers}")
                            room.version?.let { append(" · $it") }
                        },
                        payload = room
                    )
                }
            )
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message
                    ?: activity.getString(R.string.multiplayer_rooms_load_failed)
                bound.textRoomsEmpty.text = msg
                bound.textRoomsEmpty.isVisible = true
            } else {
                bound.textRoomsEmpty.isVisible = rooms.isEmpty()
                if (rooms.isEmpty()) {
                    bound.textRoomsEmpty.text = activity.getString(R.string.multiplayer_rooms_empty)
                }
            }
        }
    }

    private fun promptCreate() {
        val playerName = lobby.resolvePlayerName(playerNameProvider())
        val versionId = versionIdProvider().trim().ifBlank { null }
        InGameRoomCreateDialog.show(
            activity = activity,
            suggestedRoomName = activity.getString(R.string.ingame_mp_default_room_name, playerName),
            versionId = versionId
        ) { settings ->
            runWork {
                lobby.createRoom(
                    minecraftPort = settings.minecraftPort,
                    playerNameHint = playerName,
                    isPublic = settings.isPublic,
                    roomName = settings.roomName.ifBlank { null },
                    roomRemark = settings.roomRemark.ifBlank { null },
                    modpackUrl = settings.modpackUrl,
                    versionId = versionId,
                    dependencyInfo = settings.dependencyInfo
                ).onFailure {
                    Toast.makeText(activity, it.message ?: "创建失败", Toast.LENGTH_LONG).show()
                }
                selectedPublicRoom = null
                renderPanel()
            }
        }
    }

    private fun joinRoom(code: String?) {
        val trimmed = code?.trim().orEmpty()
        if (trimmed.length < 10) {
            Toast.makeText(activity, R.string.ingame_mp_invalid_code, Toast.LENGTH_SHORT).show()
            return
        }
        runWork {
            lobby.joinRoom(trimmed)
                .onFailure {
                    Toast.makeText(activity, it.message ?: "加入失败", Toast.LENGTH_LONG).show()
                }
                .onSuccess {
                    selectedPublicRoom = null
                    clearHostDepsCache()
                }
            renderPanel()
        }
    }

    private fun copyRoomCode() {
        val code = lobby.lobby.value?.roomCode
        if (code.isNullOrBlank()) {
            Toast.makeText(activity, R.string.ingame_mp_idle, Toast.LENGTH_SHORT).show()
        } else {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("room", code))
            Toast.makeText(activity, R.string.ingame_mp_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun runWork(block: suspend () -> Unit) {
        val b = binding ?: return
        workJob?.cancel()
        b.progress.isVisible = true
        workJob = activity.lifecycleScope.launch {
            try {
                block()
            } finally {
                binding?.progress?.isVisible = false
            }
        }
    }
}
