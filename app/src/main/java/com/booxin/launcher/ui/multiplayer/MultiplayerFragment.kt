package com.booxin.launcher.ui.multiplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.multiplayer.BlockedUser
import com.booxin.launcher.core.multiplayer.BooxinAuthSession
import com.booxin.launcher.core.multiplayer.BooxinFriend
import com.booxin.launcher.core.multiplayer.ChatConversation
import com.booxin.launcher.core.multiplayer.FriendRequest
import com.booxin.launcher.core.multiplayer.LobbyUser
import com.booxin.launcher.core.multiplayer.PublicRoom
import com.booxin.launcher.core.multiplayer.RoomInvite
import com.booxin.launcher.core.multiplayer.RoomMember
import com.booxin.launcher.core.multiplayer.SearchUser
import com.booxin.launcher.databinding.FragmentMultiplayerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class MultiplayerFragment : Fragment() {

    private enum class AuthMode { PASSWORD, EMAIL, REGISTER, FORGOT }

    private var _binding: FragmentMultiplayerBinding? = null
    private val binding get() = _binding!!
    private var authMode = AuthMode.PASSWORD

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadAvatar(uri)
    }

    private val friendsAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            when (val p = item.payload) {
                is BooxinFriend -> openChat(p.userId, p.username)
                else -> Unit
            }
        },
        onSecondary = { item ->
            val friend = item.payload as? BooxinFriend ?: return@MultiplayerUserAdapter
            when (item.secondaryLabel) {
                getString(R.string.multiplayer_invite_friend) -> inviteFriend(friend)
                getString(R.string.multiplayer_join_friend_room) -> {
                    binding.inputRoomCode.setText(friend.roomCode.orEmpty())
                    binding.tabMultiplayer.getTabAt(0)?.select()
                    joinRoom()
                }
                else -> confirmRemoveFriend(friend)
            }
        }
    )
    private val requestsAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val req = item.payload as? FriendRequest ?: return@MultiplayerUserAdapter
            actOnRequest(req, accept = true)
        },
        onSecondary = { item ->
            val req = item.payload as? FriendRequest ?: return@MultiplayerUserAdapter
            actOnRequest(req, accept = false)
        }
    )
    private val invitesAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val invite = item.payload as? RoomInvite ?: return@MultiplayerUserAdapter
            binding.inputRoomCode.setText(invite.roomCode)
            binding.tabMultiplayer.getTabAt(0)?.select()
            joinRoom()
        },
        onSecondary = { item ->
            val invite = item.payload as? RoomInvite ?: return@MultiplayerUserAdapter
            viewLifecycleOwner.lifecycleScope.launch {
                AppContainer.multiplayerAuth.dismissInvite(invite.inviteId)
                refreshFriends()
            }
        }
    )
    private val lobbyAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val user = item.payload as? LobbyUser ?: return@MultiplayerUserAdapter
            addFriend(user.id, user.username)
        }
    )
    private val searchAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val user = item.payload as? SearchUser ?: return@MultiplayerUserAdapter
            addFriend(user.id, user.username)
        }
    )
    private val roomsAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val room = item.payload as? PublicRoom ?: return@MultiplayerUserAdapter
            binding.inputRoomCode.setText(room.roomCode)
            joinRoom()
        }
    )
    private val roomMembersAdapter = MultiplayerUserAdapter()
    private val blockedAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val blocked = item.payload as? BlockedUser ?: return@MultiplayerUserAdapter
            viewLifecycleOwner.lifecycleScope.launch {
                AppContainer.multiplayerAuth.unblockUser(blocked.userId)
                refreshFriends()
            }
        }
    )
    private val conversationsAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val c = item.payload as? ChatConversation ?: return@MultiplayerUserAdapter
            openChat(c.peerUserId, c.peerUsername)
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMultiplayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listOf(
            binding.recyclerFriends to friendsAdapter,
            binding.recyclerRequests to requestsAdapter,
            binding.recyclerInvites to invitesAdapter,
            binding.recyclerLobby to lobbyAdapter,
            binding.recyclerSearch to searchAdapter,
            binding.recyclerRooms to roomsAdapter,
            binding.recyclerRoomMembers to roomMembersAdapter,
            binding.recyclerBlocked to blockedAdapter,
            binding.recyclerConversations to conversationsAdapter
        ).forEach { (rv, adapter) ->
            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.adapter = adapter
        }

        setupTabs()
        setupFriendsSubTabs()
        setupAuthChips()
        setupAccountActions()

        binding.buttonLogin.setOnClickListener { submitAuth() }
        binding.buttonSendCode.setOnClickListener { sendCode() }
        binding.buttonLogout.setOnClickListener {
            AppContainer.multiplayerAuth.logout()
            binding.textStatus.text = getString(R.string.multiplayer_logged_out)
        }
        binding.buttonSearch.setOnClickListener { doSearch() }
        binding.inputSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(); true
            } else false
        }
        binding.buttonRefreshFriends.setOnClickListener { refreshFriends() }
        binding.buttonRefreshLobby.setOnClickListener { refreshLobby() }
        binding.buttonRefreshRooms.setOnClickListener { refreshRooms() }
        binding.buttonRefreshMessages.setOnClickListener { refreshMessages() }
        binding.buttonJoinRoom.setOnClickListener { joinRoom() }
        binding.buttonLeaveRoom.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                AppContainer.multiplayerAuth.leaveActiveRoom()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppContainer.multiplayerAuth.session.collect { session ->
                        renderSession(session)
                        if (session != null) {
                            refreshFriends()
                            refreshLobby()
                            refreshRooms()
                            refreshMessages()
                        }
                    }
                }
                launch {
                    AppContainer.multiplayerAuth.joinStatus.collect { status ->
                        binding.textJoinStatus.text = status.orEmpty()
                    }
                }
                launch {
                    AppContainer.multiplayerAuth.directConnectAddress.collect { addr ->
                        binding.textDirectConnect.isVisible = !addr.isNullOrBlank()
                        binding.textDirectConnect.text = if (addr.isNullOrBlank()) {
                            ""
                        } else {
                            getString(R.string.multiplayer_direct_connect, addr)
                        }
                    }
                }
                launch {
                    AppContainer.multiplayerAuth.roomMembers.collect { members ->
                        renderRoomMembers(members)
                    }
                }
            }
        }
    }

    private fun renderRoomMembers(members: List<RoomMember>) {
        val inRoom = AppContainer.multiplayerAuth.activeLobby.value != null || members.isNotEmpty()
        binding.textRoomMembersTitle.isVisible = inRoom
        binding.recyclerRoomMembers.isVisible = inRoom && members.isNotEmpty()
        binding.textRoomMembersEmpty.isVisible = inRoom && members.isEmpty()
        if (!inRoom) {
            roomMembersAdapter.submit(emptyList())
            return
        }
        binding.textRoomMembersTitle.text = getString(
            R.string.multiplayer_room_members
        ) + "（${members.size}）"
        roomMembersAdapter.submit(
            members.mapIndexed { index, m ->
                val role = when {
                    m.isHost -> getString(R.string.multiplayer_member_host)
                    else -> getString(R.string.multiplayer_member_guest)
                }
                MultiplayerListItem(
                    id = m.machineId.ifBlank { "${m.name}-$index" },
                    name = m.name,
                    meta = buildString {
                        append(role)
                        if (m.vendor.isNotBlank()) append(" · ${m.vendor}")
                    },
                    payload = m
                )
            }
        )
    }

    private fun setupTabs() {
        val tabs = listOf(
            R.string.multiplayer_tab_rooms,
            R.string.multiplayer_tab_friends,
            R.string.multiplayer_tab_lobby,
            R.string.multiplayer_tab_messages,
            R.string.multiplayer_tab_account
        )
        tabs.forEach { binding.tabMultiplayer.addTab(binding.tabMultiplayer.newTab().setText(it)) }
        binding.tabMultiplayer.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPage(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        showPage(0)
    }

    private fun setupFriendsSubTabs() {
        val subTabs = listOf(
            R.string.multiplayer_subtab_friends,
            R.string.multiplayer_subtab_requests,
            R.string.multiplayer_subtab_invites,
            R.string.multiplayer_subtab_blocked
        )
        subTabs.forEach { binding.tabFriendsSub.addTab(binding.tabFriendsSub.newTab().setText(it)) }
        binding.tabFriendsSub.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showFriendsSubPage(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        showFriendsSubPage(0)
    }

    private fun showPage(index: Int) {
        binding.pageRooms.isVisible = index == 0
        binding.pageFriends.isVisible = index == 1
        binding.pageLobby.isVisible = index == 2
        binding.pageMessages.isVisible = index == 3
        binding.pageAccount.isVisible = index == 4
    }

    private fun showFriendsSubPage(index: Int) {
        binding.panelFriendsList.isVisible = index == 0
        binding.panelRequestsList.isVisible = index == 1
        binding.panelInvitesList.isVisible = index == 2
        binding.panelBlockedList.isVisible = index == 3
    }

    private fun setupAuthChips() {
        binding.chipAuthMode.setOnCheckedStateChangeListener { _, _ ->
            authMode = when {
                binding.chipEmailLogin.isChecked -> AuthMode.EMAIL
                binding.chipRegister.isChecked -> AuthMode.REGISTER
                binding.chipForgot.isChecked -> AuthMode.FORGOT
                else -> AuthMode.PASSWORD
            }
            applyAuthModeUi()
        }
        applyAuthModeUi()
    }

    private fun applyAuthModeUi() {
        val needUser = authMode == AuthMode.PASSWORD || authMode == AuthMode.REGISTER
        val needPass = authMode == AuthMode.PASSWORD || authMode == AuthMode.REGISTER || authMode == AuthMode.FORGOT
        val needEmail = authMode != AuthMode.PASSWORD
        val needCode = authMode != AuthMode.PASSWORD
        binding.layoutUsername.isVisible = needUser
        binding.layoutPassword.isVisible = needPass
        binding.layoutEmail.isVisible = needEmail
        binding.rowCode.isVisible = needCode
        binding.buttonLogin.text = when (authMode) {
            AuthMode.PASSWORD -> getString(R.string.multiplayer_login)
            AuthMode.EMAIL -> getString(R.string.multiplayer_email_login)
            AuthMode.REGISTER -> getString(R.string.multiplayer_register)
            AuthMode.FORGOT -> getString(R.string.multiplayer_reset_password)
        }
        if (authMode == AuthMode.FORGOT) {
            binding.layoutPassword.hint = getString(R.string.multiplayer_new_password)
        } else {
            binding.layoutPassword.hint = getString(R.string.multiplayer_password)
        }
    }

    private fun submitAuth() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.buttonLogin.isEnabled = false
            val result: Result<String> = when (authMode) {
                AuthMode.PASSWORD -> {
                    val u = binding.inputUsername.text?.toString().orEmpty().trim()
                    val p = binding.inputPassword.text?.toString().orEmpty()
                    if (u.isEmpty() || p.isEmpty()) {
                        toast(R.string.multiplayer_need_credentials)
                        binding.buttonLogin.isEnabled = true
                        return@launch
                    }
                    binding.textStatus.text = getString(R.string.multiplayer_logging_in)
                    AppContainer.multiplayerAuth.login(u, p).map { it.user.username }
                }
                AuthMode.EMAIL -> {
                    val email = binding.inputEmail.text?.toString().orEmpty().trim()
                    val code = binding.inputEmailCode.text?.toString().orEmpty().trim()
                    if (email.isEmpty() || code.isEmpty()) {
                        toast(R.string.multiplayer_need_email_code)
                        binding.buttonLogin.isEnabled = true
                        return@launch
                    }
                    binding.textStatus.text = getString(R.string.multiplayer_logging_in)
                    AppContainer.multiplayerAuth.loginWithEmail(email, code).map { it.user.username }
                }
                AuthMode.REGISTER -> {
                    val u = binding.inputUsername.text?.toString().orEmpty().trim()
                    val p = binding.inputPassword.text?.toString().orEmpty()
                    val email = binding.inputEmail.text?.toString().orEmpty().trim()
                    val code = binding.inputEmailCode.text?.toString().orEmpty().trim()
                    if (u.isEmpty() || p.isEmpty() || email.isEmpty() || code.isEmpty()) {
                        toast(R.string.multiplayer_need_register_fields)
                        binding.buttonLogin.isEnabled = true
                        return@launch
                    }
                    binding.textStatus.text = getString(R.string.multiplayer_registering)
                    AppContainer.multiplayerAuth.register(u, p, email, code).map { it.user.username }
                }
                AuthMode.FORGOT -> {
                    val email = binding.inputEmail.text?.toString().orEmpty().trim()
                    val code = binding.inputEmailCode.text?.toString().orEmpty().trim()
                    val p = binding.inputPassword.text?.toString().orEmpty()
                    if (email.isEmpty() || code.isEmpty() || p.isEmpty()) {
                        toast(R.string.multiplayer_need_reset_fields)
                        binding.buttonLogin.isEnabled = true
                        return@launch
                    }
                    AppContainer.multiplayerAuth.resetPassword(email, code, p)
                }
            }
            binding.buttonLogin.isEnabled = true
            if (result.isSuccess) {
                binding.inputPassword.setText("")
                binding.inputEmailCode.setText("")
                binding.textStatus.text = if (authMode == AuthMode.FORGOT) {
                    result.getOrThrow()
                } else {
                    getString(R.string.multiplayer_login_ok, result.getOrThrow())
                }
            } else {
                val msg = result.exceptionOrNull()?.message ?: "unknown"
                binding.textStatus.text = msg
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sendCode() {
        val email = binding.inputEmail.text?.toString().orEmpty().trim()
        if (email.isEmpty()) {
            toast(R.string.multiplayer_need_email)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            binding.buttonSendCode.isEnabled = false
            val result = when (authMode) {
                AuthMode.REGISTER -> AppContainer.multiplayerAuth.sendRegisterCode(email)
                AuthMode.EMAIL -> AppContainer.multiplayerAuth.sendEmailLoginCode(email)
                AuthMode.FORGOT -> AppContainer.multiplayerAuth.sendPasswordResetCode(email)
                AuthMode.PASSWORD -> Result.failure(IllegalStateException("无需验证码"))
            }
            binding.buttonSendCode.isEnabled = true
            if (result.isSuccess) {
                Toast.makeText(requireContext(), result.getOrThrow(), Toast.LENGTH_SHORT).show()
                binding.textStatus.text = result.getOrThrow()
            } else {
                val msg = getString(
                    R.string.multiplayer_send_code_failed,
                    result.exceptionOrNull()?.message ?: "unknown"
                )
                binding.textStatus.text = msg
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun joinRoom() {
        val code = binding.inputRoomCode.text?.toString().orEmpty().trim()
        if (code.isEmpty()) {
            toast(R.string.multiplayer_need_room_code)
            return
        }
        if (AppContainer.multiplayerAuth.current() == null) {
            toast(R.string.multiplayer_need_login_first)
            binding.tabMultiplayer.getTabAt(4)?.select()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            binding.buttonJoinRoom.isEnabled = false
            val result = AppContainer.multiplayerAuth.joinRoomCode(code)
            binding.buttonJoinRoom.isEnabled = true
            if (result.isSuccess) {
                val joined = result.getOrThrow()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.multiplayer_join_ok, joined.directConnectAddress),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    requireContext(),
                    result.exceptionOrNull()?.message ?: "加入失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun refreshRooms() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = AppContainer.multiplayerAuth.listPublicRooms()
            val rooms = result.getOrNull().orEmpty().map { room ->
                MultiplayerListItem(
                    id = room.id.ifBlank { room.roomCode },
                    name = "${room.hostName} · ${room.roomCode}",
                    meta = buildString {
                        append(room.motd.ifBlank { room.remark.orEmpty() }.ifBlank { "公开房间" })
                        append(" · ${room.currentPlayers}/${room.maxPlayers}")
                        room.version?.let { append(" · $it") }
                    },
                    primaryLabel = getString(R.string.multiplayer_join_room),
                    payload = room
                )
            }
            roomsAdapter.submit(rooms)
            binding.textRoomsEmpty.isVisible = rooms.isEmpty()
            if (result.isFailure) {
                binding.textStatus.text = result.exceptionOrNull()?.message
            }
        }
    }

    private fun refreshFriends() {
        if (AppContainer.multiplayerAuth.current() == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            AppContainer.multiplayerAuth.refreshProfile()
            AppContainer.multiplayerAuth.bumpPresence(
                isInRoom = AppContainer.multiplayerAuth.activeLobby.value != null,
                roomCode = AppContainer.multiplayerAuth.activeLobby.value?.roomCode
            )
            val friendsResult = AppContainer.multiplayerAuth.loadFriends()
            val dash = friendsResult.getOrNull()

            val inRoom = AppContainer.multiplayerAuth.activeLobby.value != null
            friendsAdapter.submit(
                dash?.friends.orEmpty().map { f ->
                    MultiplayerListItem(
                        id = f.userId,
                        name = f.username,
                        meta = friendStatus(f),
                        avatarUrl = f.avatarUrl,
                        primaryLabel = getString(R.string.multiplayer_chat),
                        secondaryLabel = friendSecondaryAction(f, inRoom),
                        payload = f
                    )
                }
            )
            binding.textFriendsEmpty.isVisible = dash?.friends.isNullOrEmpty()

            requestsAdapter.submit(
                dash?.incomingRequests.orEmpty().map { r ->
                    MultiplayerListItem(
                        id = r.requestId,
                        name = r.username,
                        meta = getString(R.string.multiplayer_incoming_request),
                        avatarUrl = r.avatarUrl,
                        primaryLabel = getString(R.string.multiplayer_accept),
                        secondaryLabel = getString(R.string.multiplayer_reject),
                        payload = r
                    )
                }
            )
            binding.textRequestsEmpty.isVisible = dash?.incomingRequests.isNullOrEmpty()

            invitesAdapter.submit(
                dash?.pendingRoomInvites.orEmpty().map { i ->
                    MultiplayerListItem(
                        id = i.inviteId,
                        name = i.fromUsername,
                        meta = getString(R.string.multiplayer_invite_meta, i.roomCode),
                        avatarUrl = i.fromAvatarUrl,
                        primaryLabel = getString(R.string.multiplayer_join_room),
                        secondaryLabel = getString(R.string.multiplayer_dismiss_invite),
                        payload = i
                    )
                }
            )
            binding.textInvitesEmpty.isVisible = dash?.pendingRoomInvites.isNullOrEmpty()

            blockedAdapter.submit(
                dash?.blockedUsers.orEmpty().map { b ->
                    MultiplayerListItem(
                        id = b.userId,
                        name = b.username,
                        meta = getString(R.string.multiplayer_blocked),
                        avatarUrl = b.avatarUrl,
                        primaryLabel = getString(R.string.multiplayer_unblock),
                        payload = b
                    )
                }
            )
            binding.textBlockedEmpty.isVisible = dash?.blockedUsers.isNullOrEmpty()

            binding.textStatus.text = getString(
                R.string.multiplayer_refreshed,
                AppContainer.multiplayerAuth.current()?.user?.username ?: ""
            )
            AppContainer.multiplayerAuth.current()?.let { renderSession(it) }
        }
    }

    private fun refreshLobby() {
        if (AppContainer.multiplayerAuth.current() == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val lobbyResult = AppContainer.multiplayerAuth.loadLobby()
            lobbyAdapter.submit(
                lobbyResult.getOrNull().orEmpty().map { u ->
                    MultiplayerListItem(
                        id = u.id,
                        name = u.username,
                        meta = statusLine(u.isOnline, u.isFriend),
                        avatarUrl = u.avatarUrl,
                        primaryLabel = if (u.isFriend) null else getString(R.string.multiplayer_add_friend),
                        payload = u
                    )
                }
            )
            binding.textLobbyEmpty.isVisible = lobbyResult.getOrNull().isNullOrEmpty()
            if (lobbyResult.isFailure) {
                binding.textStatus.text = lobbyResult.exceptionOrNull()?.message
            }
        }
    }

    private fun friendSecondaryAction(friend: BooxinFriend, selfInRoom: Boolean): String =
        when {
            selfInRoom && friend.isOnline && !friend.isInRoom ->
                getString(R.string.multiplayer_invite_friend)
            friend.isInRoom && !friend.roomCode.isNullOrBlank() ->
                getString(R.string.multiplayer_join_friend_room)
            else -> getString(R.string.multiplayer_remove_friend)
        }

    private fun refreshMessages() {
        if (AppContainer.multiplayerAuth.current() == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = AppContainer.multiplayerAuth.listConversations()
            val items = result.getOrNull().orEmpty().map { c ->
                MultiplayerListItem(
                    id = c.peerUserId,
                    name = c.peerUsername,
                    meta = buildString {
                        append(c.lastMessageBody.orEmpty().ifBlank { "…" })
                        if (c.unreadCount > 0) append(" · ${c.unreadCount}")
                    },
                    avatarUrl = c.peerAvatarUrl,
                    primaryLabel = getString(R.string.multiplayer_chat),
                    payload = c
                )
            }
            conversationsAdapter.submit(items)
            binding.textConversationsEmpty.isVisible = items.isEmpty()
        }
    }

    private fun doSearch() {
        val query = binding.inputSearch.text?.toString().orEmpty().trim()
        if (query.isEmpty()) {
            toast(R.string.multiplayer_need_search)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = AppContainer.multiplayerAuth.searchUsers(query)
            if (result.isFailure) {
                binding.textStatus.text = result.exceptionOrNull()?.message
                return@launch
            }
            searchAdapter.submit(
                result.getOrThrow().map { u ->
                    MultiplayerListItem(
                        id = u.id,
                        name = u.username,
                        meta = statusLine(u.isOnline, u.isFriend),
                        avatarUrl = u.avatarUrl,
                        primaryLabel = if (u.isFriend) null else getString(R.string.multiplayer_add_friend),
                        payload = u
                    )
                }
            )
        }
    }

    private fun openChat(peerId: String, peerName: String) {
        startActivity(
            Intent(requireContext(), ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_PEER_ID, peerId)
                .putExtra(ChatActivity.EXTRA_PEER_NAME, peerName)
        )
    }

    private fun addFriend(userId: String, username: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = AppContainer.multiplayerAuth.sendFriendRequest(userId, username)
            Toast.makeText(
                requireContext(),
                result.getOrElse { it.message ?: "failed" },
                Toast.LENGTH_SHORT
            ).show()
            if (result.isSuccess) refreshFriends()
        }
    }

    private fun actOnRequest(req: FriendRequest, accept: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (accept) {
                AppContainer.multiplayerAuth.acceptFriendRequest(req.requestId)
            } else {
                AppContainer.multiplayerAuth.rejectFriendRequest(req.requestId)
            }
            Toast.makeText(
                requireContext(),
                result.getOrElse { it.message ?: "failed" },
                Toast.LENGTH_SHORT
            ).show()
            refreshFriends()
        }
    }

    private fun confirmRemoveFriend(friend: BooxinFriend) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.multiplayer_remove_friend)
            .setMessage(getString(R.string.multiplayer_remove_confirm, friend.username))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppContainer.multiplayerAuth.removeFriend(friend.userId)
                    refreshFriends()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun friendStatus(friend: BooxinFriend): String = buildString {
        friend.relationship?.takeIf { it.isNotBlank() && it != "好友" }?.let {
            append(it)
            append(" · ")
        }
        append(if (friend.isOnline) getString(R.string.multiplayer_online) else getString(R.string.multiplayer_offline))
        if (friend.isInRoom && !friend.roomCode.isNullOrBlank()) {
            append(" · ")
            append(getString(R.string.multiplayer_in_room, friend.roomCode))
        }
    }

    private fun statusLine(online: Boolean, friend: Boolean): String = buildString {
        append(if (online) getString(R.string.multiplayer_online) else getString(R.string.multiplayer_offline))
        if (friend) {
            append(" · ")
            append(getString(R.string.multiplayer_is_friend))
        }
    }

    private fun setupAccountActions() {
        binding.buttonPickAvatar.setOnClickListener { pickAvatar.launch("image/*") }
        binding.buttonDeleteAvatar.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val result = AppContainer.multiplayerAuth.deleteAvatar()
                toastResult(result, R.string.multiplayer_avatar_deleted)
                renderSession(AppContainer.multiplayerAuth.current())
            }
        }
        binding.buttonSaveSignature.setOnClickListener {
            val signature = binding.inputSignature.text?.toString().orEmpty()
            viewLifecycleOwner.lifecycleScope.launch {
                val result = AppContainer.multiplayerAuth.updateSignature(signature)
                toastResult(result, R.string.multiplayer_signature_saved)
                renderSession(AppContainer.multiplayerAuth.current())
            }
        }
        binding.buttonSendBindCode.setOnClickListener {
            val email = binding.inputBindEmail.text?.toString().orEmpty().trim()
            if (email.isEmpty()) {
                toast(R.string.multiplayer_need_email)
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val result = AppContainer.multiplayerAuth.sendBindEmailCode(email)
                Toast.makeText(
                    requireContext(),
                    result.getOrElse { it.message ?: "failed" },
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        binding.buttonBindEmail.setOnClickListener {
            val email = binding.inputBindEmail.text?.toString().orEmpty().trim()
            val code = binding.inputBindEmailCode.text?.toString().orEmpty().trim()
            if (email.isEmpty() || code.isEmpty()) {
                toast(R.string.multiplayer_need_bind_email_fields)
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val result = AppContainer.multiplayerAuth.verifyBindEmail(email, code)
                toastResult(result, R.string.multiplayer_email_bound_ok)
                renderSession(AppContainer.multiplayerAuth.current())
            }
        }
        binding.buttonUnbindEmail.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.multiplayer_unbind_email)
                .setMessage(R.string.multiplayer_unbind_email)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = AppContainer.multiplayerAuth.unbindEmail()
                        toastResult(result, R.string.multiplayer_email_unbound_ok)
                        renderSession(AppContainer.multiplayerAuth.current())
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.buttonChangePassword.setOnClickListener {
            val current = binding.inputCurrentPassword.text?.toString().orEmpty()
            val next = binding.inputNewPassword.text?.toString().orEmpty()
            if (current.isEmpty() || next.isEmpty()) {
                toast(R.string.multiplayer_need_password_fields)
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val result = AppContainer.multiplayerAuth.changePassword(current, next)
                if (result.isSuccess) {
                    binding.inputCurrentPassword.setText("")
                    binding.inputNewPassword.setText("")
                }
                toastResult(result, R.string.multiplayer_password_changed)
            }
        }
    }

    private fun uploadAvatar(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(requireContext(), "无法读取图片", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val mime = requireContext().contentResolver.getType(uri) ?: "image/jpeg"
            val result = AppContainer.multiplayerAuth.uploadAvatar(bytes, mime)
            toastResult(result, R.string.multiplayer_avatar_updated)
            renderSession(AppContainer.multiplayerAuth.current())
        }
    }

    private fun inviteFriend(friend: BooxinFriend) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = AppContainer.multiplayerAuth.sendRoomInvite(friend.userId)
            Toast.makeText(
                requireContext(),
                result.getOrElse { it.message ?: "failed" },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun <T> toastResult(result: Result<T>, okRes: Int) {
        if (result.isSuccess) {
            toast(okRes)
        } else {
            Toast.makeText(
                requireContext(),
                result.exceptionOrNull()?.message ?: "failed",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun renderSession(session: BooxinAuthSession?) {
        val loggedIn = session != null
        binding.panelLogin.isVisible = !loggedIn
        binding.panelSession.isVisible = loggedIn
        if (session != null) {
            val user = session.user
            binding.textSessionUser.text = user.username
            binding.textSessionMeta.text = buildString {
                append(getString(R.string.multiplayer_session_ok))
                append(" · ID ")
                append(user.id.take(8))
            }
            binding.inputSignature.setText(user.signature.orEmpty())
            binding.imageSessionAvatar.loadBooxinAvatar(user.avatarUrl)
            if (!user.email.isNullOrBlank()) {
                binding.inputBindEmail.setText(user.email)
                binding.textEmailStatus.text = buildString {
                    append(getString(R.string.multiplayer_email_bound, user.email))
                    if (user.isEmailVerified) append(getString(R.string.multiplayer_email_verified))
                }
            } else {
                binding.textEmailStatus.text = getString(R.string.multiplayer_email_unbound)
            }
        }
    }

    private fun toast(res: Int) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
