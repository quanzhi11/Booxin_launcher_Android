package com.booxin.launcher.ui.launch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.R
import com.booxin.launcher.core.multiplayer.BooxinFriend
import com.booxin.launcher.core.multiplayer.BooxinMultiplayerApi
import com.booxin.launcher.core.multiplayer.InGameLobbyController
import com.booxin.launcher.core.multiplayer.PublicRoom
import com.booxin.launcher.core.multiplayer.RoomMember
import com.booxin.launcher.databinding.DialogIngameInviteFriendsBinding
import com.booxin.launcher.databinding.DialogIngameMultiplayerBinding
import com.booxin.launcher.ui.multiplayer.MultiplayerListItem
import com.booxin.launcher.ui.multiplayer.MultiplayerUserAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * In-game floating-menu multiplayer panel with browse / room detail / in-room states.
 * Uses a content overlay (like community) so Material dialog chrome does not eat space.
 */
class InGameMultiplayerPanel(
    private val activity: AppCompatActivity,
    private val playerNameProvider: () -> String = { "Player" },
    private val versionIdProvider: () -> String = { "" }
) {
    private val lobby = InGameLobbyController(
        context = activity.applicationContext
    )
    private val socialApi = BooxinMultiplayerApi()
    private var workJob: Job? = null
    private var roomsJob: Job? = null
    private var membersJob: Job? = null
    private var statusJob: Job? = null
    private var lobbyJob: Job? = null
    private var overlay: View? = null
    private var inviteDialog: android.app.Dialog? = null
    private var binding: DialogIngameMultiplayerBinding? = null
    private var selectedPublicRoom: PublicRoom? = null
    private var cachedPublicRooms: List<PublicRoom> = emptyList()
    private var showingCreate = false
    private var showingPublicList = false
    private var createForm: InGameRoomCreateForm? = null

    private val roomsAdapter = MultiplayerUserAdapter(
        onItemClick = { item ->
            val room = item.payload as? PublicRoom ?: return@MultiplayerUserAdapter
            showPublicRoomDetail(room)
        }
    )

    private val membersAdapter = MultiplayerUserAdapter()

    fun show() {
        if (lobby.loadSession() == null) {
            Toast.makeText(activity, R.string.ingame_mp_no_session, Toast.LENGTH_LONG).show()
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return

        closePanel()

        val parent = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val inflater = LayoutInflater.from(activity)
        val panelBinding = DialogIngameMultiplayerBinding.inflate(inflater, parent, false)
        binding = panelBinding
        overlay = panelBinding.root

        panelBinding.recyclerPublicRooms.layoutManager = LinearLayoutManager(activity)
        panelBinding.recyclerPublicRooms.adapter = roomsAdapter
        panelBinding.recyclerMembers.layoutManager = LinearLayoutManager(activity)
        panelBinding.recyclerMembers.adapter = membersAdapter

        panelBinding.buttonClose.setOnClickListener { closePanel() }
        panelBinding.root.setOnClickListener { closePanel() }
        panelBinding.mpPanel.setOnClickListener { /* swallow */ }

        panelBinding.buttonCreate.setOnClickListener {
            try {
                promptCreate()
            } catch (t: Throwable) {
                Toast.makeText(
                    activity,
                    t.message ?: t.javaClass.simpleName,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        panelBinding.buttonJoinCode.setOnClickListener {
            joinRoom(panelBinding.inputRoomCode.text?.toString())
        }
        panelBinding.buttonOpenPublicRooms.setOnClickListener {
            selectedPublicRoom = null
            showingCreate = false
            showingPublicList = true
            renderPanel()
            refreshPublicRooms()
        }
        panelBinding.buttonBackPublicList.setOnClickListener {
            showingPublicList = false
            renderPanel()
        }
        panelBinding.buttonRefreshRooms.setOnClickListener { refreshPublicRooms() }
        panelBinding.inputPublicRoomSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyPublicRoomFilter()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        panelBinding.buttonLeave.setOnClickListener {
            runWork {
                lobby.leave()
                selectedPublicRoom = null
                showingPublicList = false
                renderPanel()
            }
        }
        panelBinding.buttonCopyCode.setOnClickListener { copyRoomCode() }
        panelBinding.buttonRefreshMembers.setOnClickListener {
            activity.lifecycleScope.launch {
                lobby.refreshMembers()
                Toast.makeText(activity, R.string.ingame_mp_working, Toast.LENGTH_SHORT).show()
            }
        }
        panelBinding.buttonInviteFriend.setOnClickListener { showInviteFriendsDialog() }
        panelBinding.buttonBack.setOnClickListener {
            selectedPublicRoom = null
            showingPublicList = true
            renderPanel()
        }
        panelBinding.buttonJoinDetail.setOnClickListener {
            joinRoom(selectedPublicRoom?.roomCode)
        }

        lobby.restoreFromSharedSession()
        startObserving()
        renderPanel()

        parent.addView(
            panelBinding.root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    /** Called from LaunchActivity before killing `:game` — unpublish host / leave guest. */
    suspend fun leaveForExit() {
        runCatching { lobby.leave() }
    }

    fun dispose() {
        closePanel()
    }

    private fun closePanel() {
        workJob?.cancel()
        roomsJob?.cancel()
        stopObserving()
        createForm?.dispose()
        createForm = null
        showingCreate = false
        showingPublicList = false
        inviteDialog?.dismiss()
        inviteDialog = null
        val root = overlay
        overlay = null
        runCatching { (root?.parent as? ViewGroup)?.removeView(root) }
        binding = null
        selectedPublicRoom = null
    }

    private fun startObserving() {
        stopObserving()
        membersJob = activity.lifecycleScope.launch {
            lobby.members.collectLatest { members ->
                if (binding == null) return@collectLatest
                renderMembers(members)
            }
        }
        statusJob = activity.lifecycleScope.launch {
            lobby.status.collectLatest {
                if (binding == null) return@collectLatest
                if (lobby.lobby.value != null) renderInRoomStatus()
                else renderPanel()
            }
        }
        lobbyJob = activity.lifecycleScope.launch {
            lobby.lobby.collectLatest {
                if (binding == null) return@collectLatest
                renderPanel()
            }
        }
    }

    private fun stopObserving() {
        membersJob?.cancel()
        membersJob = null
        statusJob?.cancel()
        statusJob = null
        lobbyJob?.cancel()
        lobbyJob = null
    }

    private fun renderPanel() {
        val b = binding ?: return
        val inRoom = lobby.lobby.value != null
        val create = !inRoom && showingCreate
        val publicList = !inRoom && !create && showingPublicList && selectedPublicRoom == null
        val detail = if (inRoom || create || !showingPublicList) null else selectedPublicRoom

        b.panelBrowse.isVisible = !inRoom && !create && !publicList && detail == null
        b.panelPublicList.isVisible = publicList
        b.panelCreate.isVisible = create
        b.panelInRoom.isVisible = inRoom
        b.panelPublicDetail.isVisible = !inRoom && !create && detail != null

        if (inRoom) {
            showingCreate = false
            showingPublicList = false
            createForm?.dispose()
            createForm = null
            renderInRoomStatus()
            renderMembers(lobby.members.value)
            return
        }

        if (create) return

        val status = lobby.status.value.orEmpty()
        if (detail != null) {
            b.textDetailHost.text =
                detail.hostName.ifBlank { activity.getString(R.string.multiplayer_tab_rooms) }
            b.textDetailMotd.text = detail.motd.ifBlank {
                detail.remark.orEmpty().ifBlank { activity.getString(R.string.multiplayer_public_rooms) }
            }
            b.textDetailMeta.text = buildString {
                if (detail.isCloudPublicRoom()) {
                    append(activity.getString(R.string.multiplayer_cloud_room_badge))
                    append(" · ")
                }
                append(
                    activity.getString(
                        R.string.multiplayer_public_room_meta,
                        detail.version?.ifBlank { "?" } ?: "?",
                        detail.currentPlayers,
                        detail.maxPlayers
                    )
                )
            }
            val cloudAddress = detail.resolveCloudDedicatedAddress()
            b.textDetailCode.text = if (detail.isCloudPublicRoom() && !cloudAddress.isNullOrBlank()) {
                activity.getString(R.string.multiplayer_cloud_room_address, cloudAddress)
            } else {
                activity.getString(R.string.multiplayer_public_room_code, detail.roomCode)
            }
            b.buttonJoinDetail.setText(
                if (detail.isCloudPublicRoom()) R.string.multiplayer_join_cloud_room
                else R.string.ingame_mp_join
            )
            b.textStatusDetail.text = status
            return
        }

        if (publicList) {
            applyPublicRoomFilter()
            return
        }

        b.textStatusBrowse.text = status.ifBlank { activity.getString(R.string.ingame_mp_idle) }
    }

    private fun renderInRoomStatus() {
        val b = binding ?: return
        val lobbyInfo = lobby.lobby.value
        val host = lobby.isHost.value
        val connect = lobby.directConnect.value
        val status = lobby.status.value.orEmpty()
        b.textRoomCode.text = if (lobbyInfo != null) {
            activity.getString(R.string.ingame_mp_room_line, lobbyInfo.roomCode)
        } else {
            activity.getString(R.string.ingame_mp_idle)
        }
        b.textStatusInRoom.text = buildString {
            if (lobbyInfo != null) {
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
    }

    private fun renderMembers(members: List<RoomMember>) {
        val b = binding ?: return
        if (!b.panelInRoom.isVisible) return
        b.textMembersTitle.text = activity.getString(R.string.ingame_mp_members_title) +
            "（${members.size}）"
        b.recyclerMembers.isVisible = members.isNotEmpty()
        b.textMembersEmpty.isVisible = members.isEmpty()
        membersAdapter.submit(
            members.mapIndexed { index, m ->
                val role = when {
                    m.isHost -> activity.getString(R.string.multiplayer_member_host)
                    m.kind.equals("GUEST", true) ->
                        activity.getString(R.string.multiplayer_member_guest)
                    else -> m.kind ?: "玩家"
                }
                MultiplayerListItem(
                    id = m.machineId.ifBlank { "${m.name}-$index" },
                    name = m.name,
                    meta = role,
                    payload = m
                )
            }
        )
    }

    private fun showInviteFriendsDialog() {
        if (lobby.lobby.value == null) {
            Toast.makeText(activity, R.string.multiplayer_invite_need_host, Toast.LENGTH_SHORT).show()
            return
        }
        val session = lobby.loadSession()
        if (session == null) {
            Toast.makeText(activity, R.string.ingame_mp_no_session, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val inviteBinding = DialogIngameInviteFriendsBinding.inflate(LayoutInflater.from(activity))
            lateinit var inviteAdapter: MultiplayerUserAdapter
            inviteAdapter = MultiplayerUserAdapter(
                onPrimary = { item ->
                    val friend = item.payload as? BooxinFriend ?: return@MultiplayerUserAdapter
                    activity.lifecycleScope.launch {
                        val result = socialApi.sendRoomInvite(session, friend.userId)
                        if (result.isSuccess) {
                            Toast.makeText(
                                activity,
                                result.getOrNull()
                                    ?: activity.getString(R.string.multiplayer_invite_sent),
                                Toast.LENGTH_SHORT
                            ).show()
                            inviteDialog?.dismiss()
                        } else {
                            Toast.makeText(
                                activity,
                                result.exceptionOrNull()?.message ?: "failed",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )
            inviteBinding.recyclerInviteFriends.layoutManager = LinearLayoutManager(activity)
            inviteBinding.recyclerInviteFriends.adapter = inviteAdapter

            inviteDialog?.dismiss()
            // Prefer system AlertDialog in :game — nested Material dialogs can kill the process.
            inviteDialog = android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.ingame_mp_invite_title)
                .setView(inviteBinding.root)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { it.show() }

            inviteBinding.progressInvite.isVisible = true
            activity.lifecycleScope.launch {
                val result = socialApi.fetchFriends(session)
                inviteBinding.progressInvite.isVisible = false
                val online = result.getOrNull()?.friends.orEmpty().filter { it.isOnline }
                inviteAdapter.submit(
                    online.map { friend ->
                        MultiplayerListItem(
                            id = friend.userId,
                            name = friend.username,
                            meta = if (friend.isInRoom) {
                                activity.getString(R.string.ingame_mp_friend_in_room)
                            } else {
                                activity.getString(R.string.multiplayer_online)
                            },
                            avatarUrl = friend.avatarUrl,
                            frameId = friend.selectedFrameId,
                            primaryLabel = activity.getString(R.string.multiplayer_invite_friend),
                            payload = friend
                        )
                    }
                )
                inviteBinding.textInviteEmpty.isVisible = online.isEmpty()
                inviteBinding.recyclerInviteFriends.isVisible = online.isNotEmpty()
                if (result.isFailure) {
                    inviteBinding.textInviteEmpty.text =
                        result.exceptionOrNull()?.message
                            ?: activity.getString(R.string.ingame_mp_invite_no_online)
                    inviteBinding.textInviteEmpty.isVisible = true
                }
            }
        } catch (t: Throwable) {
            Toast.makeText(
                activity,
                t.message ?: t.javaClass.simpleName,
                Toast.LENGTH_LONG
            ).show()
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
            cachedPublicRooms = result.getOrNull().orEmpty()
            applyPublicRoomFilter()
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message
                    ?: activity.getString(R.string.multiplayer_rooms_load_failed)
                bound.textRoomsEmpty.text = msg
                bound.textRoomsEmpty.isVisible = true
            } else if (cachedPublicRooms.isEmpty()) {
                bound.textRoomsEmpty.text = activity.getString(R.string.multiplayer_rooms_empty)
                bound.textRoomsEmpty.isVisible = true
            }
        }
    }

    private fun applyPublicRoomFilter() {
        val b = binding ?: return
        val query = b.inputPublicRoomSearch.text?.toString()?.trim().orEmpty()
        val filtered = if (query.isEmpty()) {
            cachedPublicRooms
        } else {
            cachedPublicRooms.filter { room ->
                listOf(
                    room.hostName,
                    room.motd,
                    room.remark,
                    room.roomCode,
                    room.version
                ).any { field ->
                    field?.contains(query, ignoreCase = true) == true
                }
            }
        }
        roomsAdapter.submit(
            filtered.map { room ->
                MultiplayerListItem(
                    id = room.id.ifBlank { room.roomCode },
                    name = "${room.hostName} · ${room.roomCode.take(8)}…",
                    meta = buildString {
                        if (room.isCloudPublicRoom()) {
                            append(activity.getString(R.string.multiplayer_cloud_room_badge))
                            append(" · ")
                        }
                        append(room.motd.ifBlank { room.remark.orEmpty() }.ifBlank {
                            activity.getString(R.string.multiplayer_public_rooms)
                        })
                        append(" · ${room.currentPlayers}/${room.maxPlayers}")
                        room.version?.let { append(" · $it") }
                    },
                    payload = room
                )
            }
        )
        b.textPublicRoomCount.text =
            activity.getString(R.string.multiplayer_room_count, filtered.size)
        if (cachedPublicRooms.isNotEmpty()) {
            b.textRoomsEmpty.isVisible = filtered.isEmpty()
            if (filtered.isEmpty()) {
                b.textRoomsEmpty.text = activity.getString(R.string.multiplayer_rooms_empty)
            }
        }
    }

    private fun promptCreate() {
        val b = binding ?: return
        if (lobby.lobby.value != null) return
        val playerName = lobby.resolvePlayerName(playerNameProvider())
        val versionId = versionIdProvider().trim().ifBlank { null }
        selectedPublicRoom = null
        showingPublicList = false
        showingCreate = true
        createForm?.dispose()
        createForm = InGameRoomCreateForm(
            activity = activity,
            panel = b,
            form = b.createForm,
            versionId = versionId,
            suggestedRoomName = activity.getString(R.string.ingame_mp_default_room_name, playerName),
            onConfirm = { settings ->
                showingCreate = false
                createForm = null
                renderPanel()
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
                    showingPublicList = false
                    renderPanel()
                }
            },
            onCancel = {
                showingCreate = false
                createForm = null
                renderPanel()
            }
        )
        renderPanel()
        createForm?.start()
    }

    private fun joinRoom(code: String?) {
        val trimmed = code?.trim().orEmpty()
        if (trimmed.length < 10) {
            Toast.makeText(activity, R.string.ingame_mp_invalid_code, Toast.LENGTH_SHORT).show()
            return
        }
        runWork {
            val selected = selectedPublicRoom?.takeIf {
                it.roomCode.equals(trimmed, ignoreCase = true)
            }
            val room = selected
                ?: lobby.getPublicRoom(trimmed).getOrNull()
                ?: cachedPublicRooms.firstOrNull { it.roomCode.equals(trimmed, ignoreCase = true) }
            if (room != null && room.isCloudPublicRoom()) {
                val address = room.resolveCloudDedicatedAddress()
                if (!address.isNullOrBlank()) {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("cloud-server", address))
                }
                Toast.makeText(
                    activity,
                    R.string.multiplayer_cloud_room_ingame_blocked,
                    Toast.LENGTH_LONG
                ).show()
                return@runWork
            }
            lobby.joinRoom(trimmed)
                .onFailure {
                    Toast.makeText(activity, it.message ?: "加入失败", Toast.LENGTH_LONG).show()
                }
                .onSuccess {
                    selectedPublicRoom = null
                    showingPublicList = false
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
