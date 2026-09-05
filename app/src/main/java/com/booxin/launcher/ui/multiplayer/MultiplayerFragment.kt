package com.booxin.launcher.ui.multiplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.download.game.GameInstallPhase
import com.booxin.launcher.core.multiplayer.BlockedUser
import com.booxin.launcher.core.multiplayer.BooxinAuthSession
import com.booxin.launcher.core.multiplayer.BooxinFriend
import com.booxin.launcher.core.multiplayer.ChatConversation
import com.booxin.launcher.core.multiplayer.FriendRequest
import com.booxin.launcher.core.multiplayer.LobbyPage
import com.booxin.launcher.core.multiplayer.LobbyUser
import com.booxin.launcher.core.multiplayer.OfficialServerCatalog
import com.booxin.launcher.core.multiplayer.OfficialServerInfo
import com.booxin.launcher.core.multiplayer.OfficialServerJoinService
import com.booxin.launcher.core.multiplayer.PublicRoom
import com.booxin.launcher.core.multiplayer.RewardProfile
import com.booxin.launcher.core.multiplayer.RewardProfileHelper
import com.booxin.launcher.core.multiplayer.RoomDependencySnapshot
import com.booxin.launcher.core.multiplayer.RoomHostDependencyInstaller
import com.booxin.launcher.core.multiplayer.RoomSessionTracker
import com.booxin.launcher.core.auth.MicrosoftAuthLogger
import com.booxin.launcher.core.auth.MicrosoftAuthService
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.ui.auth.MicrosoftAuthErrorDialog
import com.booxin.launcher.core.multiplayer.RoomInvite
import com.booxin.launcher.core.multiplayer.RoomInviteCoordinator
import com.booxin.launcher.core.multiplayer.RoomMember
import com.booxin.launcher.core.multiplayer.SearchUser
import com.booxin.launcher.databinding.FragmentMultiplayerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MultiplayerFragment : Fragment() {

    private enum class AuthMode { PASSWORD, EMAIL, REGISTER, FORGOT }

    private enum class RoomsLeftMode { BROWSE, PUBLIC_LIST, PUBLIC_DETAIL }

    private var _binding: FragmentMultiplayerBinding? = null
    private val binding get() = _binding!!
    private var authMode = AuthMode.PASSWORD
    private var rewardProfile: RewardProfile? = null
    private var cachedOfficialServer: OfficialServerInfo? = null
    private var officialJoinInProgress = false
    private var roomsLeftMode = RoomsLeftMode.BROWSE
    private var cachedPublicRooms: List<PublicRoom> = emptyList()
    private var selectedPublicRoom: PublicRoom? = null
    private val officialJoinService = OfficialServerJoinService()
    private var askedDmNotificationPermission = false
    private var cachedHostDepsRoomCode: String? = null
    private var cachedHostDeps: RoomDependencySnapshot? = null
    private var hostDepsDownloadInProgress = false
    private var hostDepsJob: Job? = null
    private var lobbyPage = 1
    private var lobbyLoading = false
    private var lastLobbyPage: LobbyPage? = null

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadAvatar(uri)
    }

    private val requestDmNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && isAdded) {
            Toast.makeText(
                requireContext(),
                R.string.multiplayer_dm_permission_hint,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val friendsAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val friend = item.payload as? BooxinFriend ?: return@MultiplayerUserAdapter
            when (item.primaryLabel) {
                getString(R.string.multiplayer_join_friend_room) -> joinFriendRoom(friend)
                getString(R.string.multiplayer_invite_friend) -> inviteFriend(friend)
                else -> openChat(friend.userId, friend.username)
            }
        },
        onSecondary = { item ->
            val friend = item.payload as? BooxinFriend ?: return@MultiplayerUserAdapter
            when (item.secondaryLabel) {
                getString(R.string.multiplayer_chat) -> openChat(friend.userId, friend.username)
                getString(R.string.multiplayer_join_friend_room) -> joinFriendRoom(friend)
                getString(R.string.multiplayer_invite_friend) -> inviteFriend(friend)
                else -> confirmRemoveFriend(friend)
            }
        },
        onItemClick = { item ->
            val friend = item.payload as? BooxinFriend ?: return@MultiplayerUserAdapter
            openUserDetail(friend.userId)
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
        },
        onItemClick = { item ->
            val req = item.payload as? FriendRequest ?: return@MultiplayerUserAdapter
            openUserDetail(req.userId)
        }
    )
    private val invitesAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val invite = item.payload as? RoomInvite ?: return@MultiplayerUserAdapter
            binding.inputRoomCode.setText(invite.roomCode)
            joinRoom()
        },
        onSecondary = { item ->
            val invite = item.payload as? RoomInvite ?: return@MultiplayerUserAdapter
            viewLifecycleOwner.lifecycleScope.launch {
                AppContainer.multiplayerAuth.dismissInvite(invite.inviteId)
                refreshFriends()
            }
        },
        onItemClick = { item ->
            val invite = item.payload as? RoomInvite ?: return@MultiplayerUserAdapter
            openUserDetail(invite.fromUserId)
        }
    )
    private val lobbyAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val user = item.payload as? LobbyUser ?: return@MultiplayerUserAdapter
            val requestId = user.pendingIncomingRequestId
            if (user.hasPendingIncomingRequest && !requestId.isNullOrBlank()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = AppContainer.multiplayerAuth.acceptFriendRequest(requestId)
                    Toast.makeText(
                        requireContext(),
                        result.getOrElse { it.message ?: "failed" },
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshLobby()
                    refreshFriends()
                }
            } else {
                addFriend(user.id, user.username) { refreshLobby() }
            }
        },
        onItemClick = { item ->
            val user = item.payload as? LobbyUser ?: return@MultiplayerUserAdapter
            openUserDetail(user.id)
        }
    )
    private val searchAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val user = item.payload as? SearchUser ?: return@MultiplayerUserAdapter
            addFriend(user.id, user.username)
        },
        onItemClick = { item ->
            val user = item.payload as? SearchUser ?: return@MultiplayerUserAdapter
            openUserDetail(user.id)
        }
    )
    private val publicRoomCardAdapter = PublicRoomCardAdapter(
        onRoomClick = { room -> showPublicRoomDetail(room) }
    )
    private val roomMembersAdapter = MultiplayerUserAdapter()
    private val blockedAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val blocked = item.payload as? BlockedUser ?: return@MultiplayerUserAdapter
            viewLifecycleOwner.lifecycleScope.launch {
                AppContainer.multiplayerAuth.unblockUser(blocked.userId)
                refreshFriends()
            }
        },
        onItemClick = { item ->
            val blocked = item.payload as? BlockedUser ?: return@MultiplayerUserAdapter
            openUserDetail(blocked.userId)
        }
    )
    private val conversationsAdapter = MultiplayerUserAdapter(
        onPrimary = { item ->
            val c = item.payload as? ChatConversation ?: return@MultiplayerUserAdapter
            openChat(c.peerUserId, c.peerUsername)
        },
        onItemClick = { item ->
            val c = item.payload as? ChatConversation ?: return@MultiplayerUserAdapter
            openUserDetail(c.peerUserId)
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
            binding.recyclerRoomMembers to roomMembersAdapter,
            binding.recyclerBlocked to blockedAdapter,
            binding.recyclerConversations to conversationsAdapter
        ).forEach { (rv, adapter) ->
            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.adapter = adapter
        }

        binding.recyclerPublicRoomsGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recyclerPublicRoomsGrid.adapter = publicRoomCardAdapter

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
        binding.buttonRefreshLobby.setOnClickListener { refreshLobby(resetPage = true) }
        binding.buttonLobbyChat.setOnClickListener { openLobbyChat() }
        binding.buttonLobbyPrevPage.setOnClickListener {
            if (lobbyLoading || lobbyPage <= 1) return@setOnClickListener
            lobbyPage--
            refreshLobby()
        }
        binding.buttonLobbyNextPage.setOnClickListener {
            if (lobbyLoading || lastLobbyPage?.hasNext != true) return@setOnClickListener
            lobbyPage++
            refreshLobby()
        }
        binding.buttonRefreshPublicRooms.setOnClickListener { refreshRooms() }
        binding.buttonOpenPublicRooms.setOnClickListener { openPublicRoomsList() }
        binding.buttonBackPublicList.setOnClickListener { closePublicRoomsList() }
        binding.inputPublicRoomSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyPublicRoomFilter()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.inputPublicRoomSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applyPublicRoomFilter(); true
            } else false
        }
        binding.buttonRefreshMessages.setOnClickListener { refreshMessages() }
        binding.buttonJoinRoom.setOnClickListener { joinRoom() }
        binding.buttonLeaveRoom.setOnClickListener { leaveRoomWithCleanup() }
        binding.buttonDirectBackupLaunch.setOnClickListener { launchDirectBackup() }
        binding.buttonDownloadHostDeps.setOnClickListener { downloadHostDependencies() }
        binding.buttonBackPublicRoom.setOnClickListener {
            selectedPublicRoom = null
            roomsLeftMode = RoomsLeftMode.PUBLIC_LIST
            renderRoomsPanel()
        }
        binding.buttonJoinPublicRoom.setOnClickListener {
            val room = selectedPublicRoom ?: return@setOnClickListener
            joinRoom(room.roomCode)
        }
        binding.buttonJoinOfficialServer.setOnClickListener { joinOfficialServer() }
        binding.buttonCheckIn.setOnClickListener { performCheckIn() }
        binding.buttonPickFrame.setOnClickListener { showFramePicker() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    var lastAuthKey: String? = null
                    AppContainer.multiplayerAuth.session.collect { session ->
                        if (_binding == null) return@collect
                        renderSession(session)
                        val authKey = session?.let { "${it.apiRoot}|${it.accessToken}" }
                        // Only bulk-refresh on login / token change — profile updates
                        // 在线状态/头像更新不要刷爆 API。
                        if (authKey != lastAuthKey) {
                            lastAuthKey = authKey
                            refreshPublicData()
                            if (session != null) {
                                ensureDmNotificationPermission()
                                refreshSocialData()
                                refreshRewards()
                                consumePendingRoomJoin()
                            }
                        }
                    }
                }
                launch {
                    AppContainer.multiplayerAuth.rewardProfile.collect { profile ->
                        rewardProfile = profile
                        if (_binding == null) return@collect
                        renderRewards(profile)
                    }
                }
                launch {
                    AppContainer.multiplayerAuth.joinStatus.collect { status ->
                        val b = _binding ?: return@collect
                        val text = status.orEmpty()
                        b.textJoinStatus.text = text
                        b.textJoinStatusInRoom.text = text
                        b.textJoinStatusDetail.text = text
                    }
                }
                launch {
                    AppContainer.multiplayerAuth.directConnectAddress.collect { addr ->
                        val b = _binding ?: return@collect
                        val visible = !addr.isNullOrBlank()
                        b.textDirectConnect.isVisible = visible
                        b.buttonDirectBackupLaunch.isVisible = visible
                        b.textDirectConnect.text = if (addr.isNullOrBlank()) {
                            ""
                        } else {
                            getString(R.string.multiplayer_direct_connect, addr)
                        }
                    }
                }
                launch {
                    AppContainer.multiplayerAuth.roomMembers.collect { members ->
                        if (_binding == null) return@collect
                        renderRoomMembers(members)
                    }
                }
                launch {
                    AppContainer.multiplayerAuth.activeLobby.collect {
                        if (_binding == null) return@collect
                        if (AppContainer.multiplayerAuth.activeLobby.value != null) {
                            selectedPublicRoom = null
                            roomsLeftMode = RoomsLeftMode.BROWSE
                        }
                        renderRoomsPanel()
                        if (AppContainer.multiplayerAuth.current() != null) {
                            refreshFriends()
                        }
                        consumePendingRoomJoin()
                    }
                }
            }
        }
        renderRoomsPanel()
    }

    override fun onResume() {
        super.onResume()
        renderRoomsPanel()
        refreshPublicData()
        if (AppContainer.multiplayerAuth.current() != null) {
            refreshSocialData()
        }
        consumePendingRoomJoin()
    }

    /** Public room list does not require login. */
    private fun refreshPublicData() {
        refreshRooms()
        refreshOfficialServerCard()
    }

    private fun refreshSocialData() {
        refreshFriends()
        refreshLobby()
        refreshMessages()
    }

    private fun renderRoomsPanel() {
        val b = _binding ?: return
        val inRoom = AppContainer.multiplayerAuth.activeLobby.value != null
        if (inRoom) {
            roomsLeftMode = RoomsLeftMode.BROWSE
            selectedPublicRoom = null
        }

        val showBrowse = !inRoom && roomsLeftMode == RoomsLeftMode.BROWSE
        val showPublicList = !inRoom && roomsLeftMode == RoomsLeftMode.PUBLIC_LIST
        val showPublicDetail = !inRoom &&
            roomsLeftMode == RoomsLeftMode.PUBLIC_DETAIL &&
            selectedPublicRoom != null

        b.roomsHeader.isVisible = showBrowse
        b.panelRoomsBrowse.isVisible = showBrowse
        b.panelRoomsPublicList.isVisible = showPublicList
        b.panelRoomsPublicDetail.isVisible = showPublicDetail
        b.panelRoomsInRoom.isVisible = inRoom
        if (inRoom) {
            refreshHostDepsUi()
        } else {
            clearHostDepsCache()
            b.buttonDownloadHostDeps.isVisible = false
            b.textHostDepsInfo.isVisible = false
        }

        if (showPublicDetail) {
            val detail = selectedPublicRoom ?: return
            b.textPublicRoomHost.text = detail.hostName.ifBlank { getString(R.string.multiplayer_tab_rooms) }
            b.textPublicRoomMotd.text = detail.motd.ifBlank {
                detail.remark.orEmpty().ifBlank { getString(R.string.multiplayer_public_rooms) }
            }
            b.textPublicRoomMeta.text = getString(
                R.string.multiplayer_public_room_meta,
                detail.version?.ifBlank { "?" } ?: "?",
                detail.currentPlayers,
                detail.maxPlayers
            )
            b.textPublicRoomCode.text = getString(R.string.multiplayer_public_room_code, detail.roomCode)
        }

        if (showPublicList) {
            applyPublicRoomFilter()
        }
    }

    private fun openPublicRoomsList() {
        if (AppContainer.multiplayerAuth.activeLobby.value != null) return
        roomsLeftMode = RoomsLeftMode.PUBLIC_LIST
        renderRoomsPanel()
        refreshRooms()
    }

    private fun closePublicRoomsList() {
        roomsLeftMode = RoomsLeftMode.BROWSE
        binding.inputPublicRoomSearch.setText("")
        renderRoomsPanel()
    }

    private fun applyPublicRoomFilter() {
        val b = _binding ?: return
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
        publicRoomCardAdapter.submit(filtered)
        b.textPublicRoomCount.text = getString(R.string.multiplayer_room_count, filtered.size)
        b.textPublicRoomsEmpty.isVisible = filtered.isEmpty()
        b.recyclerPublicRoomsGrid.isVisible = filtered.isNotEmpty()
    }

    private fun showPublicRoomDetail(room: PublicRoom) {
        if (AppContainer.multiplayerAuth.activeLobby.value != null) return
        selectedPublicRoom = room
        roomsLeftMode = RoomsLeftMode.PUBLIC_DETAIL
        renderRoomsPanel()
    }

    private fun renderRoomMembers(members: List<RoomMember>) {
        val binding = _binding ?: return
        val inRoom = AppContainer.multiplayerAuth.activeLobby.value != null || members.isNotEmpty()
        if (!binding.panelRoomsInRoom.isVisible) {
            binding.textRoomMembersTitle.isVisible = false
            binding.recyclerRoomMembers.isVisible = false
            binding.textRoomMembersEmpty.isVisible = false
            if (!inRoom) roomMembersAdapter.submit(emptyList())
            return
        }
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
        // Landscape: rooms stay on the left; right rail switches social pages.
        val tabs = listOf(
            R.string.multiplayer_tab_friends,
            R.string.multiplayer_tab_lobby,
            R.string.multiplayer_tab_messages,
            R.string.multiplayer_tab_account
        )
        tabs.forEach { binding.tabMultiplayer.addTab(binding.tabMultiplayer.newTab().setText(it)) }
        binding.tabMultiplayer.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showSocialPage(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        if (AppContainer.multiplayerAuth.current() == null) {
            binding.tabMultiplayer.getTabAt(3)?.select()
            showSocialPage(3)
        } else {
            binding.tabMultiplayer.getTabAt(0)?.select()
            showSocialPage(0)
        }
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

    private fun showSocialPage(index: Int) {
        binding.pageFriends.isVisible = index == 0
        binding.pageLobby.isVisible = index == 1
        binding.pageMessages.isVisible = index == 2
        binding.pageAccount.isVisible = index == 3
    }

    private fun selectAccountTab() {
        binding.tabMultiplayer.getTabAt(3)?.select()
    }

    private fun showFriendsSubPage(index: Int) {
        binding.panelFriendsList.isVisible = index == 0
        binding.panelRequestsList.isVisible = index == 1
        binding.panelInvitesList.isVisible = index == 2
        binding.panelBlockedList.isVisible = index == 3
    }

    private fun setupAuthChips() {
        val tabs = binding.tabAuthPrimary
        if (tabs.tabCount == 0) {
            tabs.addTab(tabs.newTab().setText(R.string.multiplayer_mode_login))
            tabs.addTab(tabs.newTab().setText(R.string.multiplayer_mode_register))
        }
        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                authMode = when (tab.position) {
                    1 -> AuthMode.REGISTER
                    else -> if (authMode == AuthMode.EMAIL) AuthMode.EMAIL else AuthMode.PASSWORD
                }
                if (tab.position == 0 && authMode != AuthMode.EMAIL && authMode != AuthMode.PASSWORD) {
                    authMode = AuthMode.PASSWORD
                }
                applyAuthModeUi()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })
        binding.buttonSwitchLoginMethod.setOnClickListener {
            authMode = if (authMode == AuthMode.EMAIL) AuthMode.PASSWORD else AuthMode.EMAIL
            binding.tabAuthPrimary.getTabAt(0)?.select()
            applyAuthModeUi()
        }
        binding.buttonForgotPassword.setOnClickListener {
            authMode = AuthMode.FORGOT
            applyAuthModeUi()
        }
        binding.buttonBackToLogin.setOnClickListener {
            authMode = AuthMode.PASSWORD
            binding.tabAuthPrimary.getTabAt(0)?.select()
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
        binding.tabAuthPrimary.isVisible = authMode != AuthMode.FORGOT
        binding.rowAuthLinks.isVisible = authMode != AuthMode.FORGOT && authMode != AuthMode.REGISTER
        binding.buttonBackToLogin.isVisible = authMode == AuthMode.FORGOT
        binding.buttonSwitchLoginMethod.text = when (authMode) {
            AuthMode.EMAIL -> getString(R.string.multiplayer_switch_to_password)
            else -> getString(R.string.multiplayer_switch_to_email)
        }
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
            val ui = _binding ?: return@launch
            ui.buttonLogin.isEnabled = true
            if (result.isSuccess) {
                ui.inputPassword.setText("")
                ui.inputEmailCode.setText("")
                ui.textStatus.text = if (authMode == AuthMode.FORGOT) {
                    result.getOrThrow()
                } else {
                    getString(R.string.multiplayer_login_ok, result.getOrThrow())
                }
            } else {
                val msg = result.exceptionOrNull()?.message ?: "unknown"
                ui.textStatus.text = msg
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
            val ui = _binding ?: return@launch
            ui.buttonSendCode.isEnabled = true
            if (result.isSuccess) {
                Toast.makeText(requireContext(), result.getOrThrow(), Toast.LENGTH_SHORT).show()
                ui.textStatus.text = result.getOrThrow()
            } else {
                val msg = getString(
                    R.string.multiplayer_send_code_failed,
                    result.exceptionOrNull()?.message ?: "unknown"
                )
                ui.textStatus.text = msg
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshOfficialServerCard() {
        viewLifecycleOwner.lifecycleScope.launch {
            val servers = withContext(Dispatchers.IO) {
                OfficialServerCatalog.ensureLoaded()
            }
            val ui = _binding ?: return@launch
            val server = servers.firstOrNull()
            cachedOfficialServer = server
            if (server == null) {
                ui.cardOfficialServer.isVisible = false
                return@launch
            }
            ui.cardOfficialServer.isVisible = true
            ui.textOfficialServerName.text = server.name
            ui.textOfficialServerDetail.text = buildString {
                append(server.version.ifBlank { "?" })
                if (server.forgeVersion.isNotBlank()) {
                    append(" · Forge ")
                    append(server.forgeVersion)
                }
                append(" · ")
                append(server.serverAddress)
            }
        }
    }

    private fun joinOfficialServer() {
        if (officialJoinInProgress) {
            toast(R.string.multiplayer_official_busy)
            return
        }
        val account = AppContainer.repository.selectedAccount()
        if (account == null) {
            toast(R.string.multiplayer_official_need_account)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            var server = cachedOfficialServer
            if (server == null) {
                server = withContext(Dispatchers.IO) {
                    OfficialServerCatalog.ensureLoaded(force = true)
                }.firstOrNull()
                cachedOfficialServer = server
            }
            val ui = _binding ?: return@launch
            if (server == null) {
                toast(R.string.multiplayer_official_missing)
                ui.cardOfficialServer.isVisible = false
                return@launch
            }

            val installed = AppContainer.repository.installedVersions.value.any { ver ->
                if (server.forgeVersion.isBlank()) {
                    ver.id.equals(server.version, ignoreCase = true)
                } else {
                    ver.id.contains(server.version, ignoreCase = true) &&
                        (ver.id.contains(server.forgeVersion, ignoreCase = true) ||
                            ver.id.contains("forge", ignoreCase = true))
                }
            }
            if (!installed) {
                val confirm = suspendCancellableCoroutine { cont ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.multiplayer_join_official)
                        .setMessage(
                            getString(
                                R.string.multiplayer_official_install_confirm,
                                server.version
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
                if (!confirm) return@launch
            }

            officialJoinInProgress = true
            ui.buttonJoinOfficialServer.isEnabled = false
            ui.panelOfficialProgress.isVisible = true
            updateOfficialProgress(-1, getString(R.string.multiplayer_official_busy))

            val progressJob: Job = viewLifecycleOwner.lifecycleScope.launch {
                AppContainer.repository.forgeInstallProgress.collect { progress ->
                    if (progress == null) return@collect
                    if (progress.phase == GameInstallPhase.IDLE) return@collect
                    val fraction = progress.fraction
                    val percent = when {
                        progress.phase == GameInstallPhase.DONE -> 80
                        progress.phase == GameInstallPhase.FAILED -> -1
                        fraction >= 0f -> (fraction * 80).toInt().coerceIn(1, 80)
                        else -> -1
                    }
                    val label = progress.message.ifBlank { progress.phase.name }
                    updateOfficialProgress(percent, label)
                    if (progress.phase == GameInstallPhase.FAILED) {
                        // Keep message visible; prepare() will also fail.
                    }
                }
            }

            val prepared = try {
                officialJoinService.prepare(requireContext(), server) { percent, message ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        updateOfficialProgress(percent, message)
                    }
                }
            } catch (cancelled: CancellationException) {
                progressJob.cancel()
                officialJoinInProgress = false
                _binding?.buttonJoinOfficialServer?.isEnabled = true
                updateOfficialProgress(-1, getString(R.string.multiplayer_official_cancelled))
                throw cancelled
            }

            progressJob.cancel()
            val end = _binding
            if (prepared.isFailure) {
                officialJoinInProgress = false
                end?.buttonJoinOfficialServer?.isEnabled = true
                val msg = prepared.exceptionOrNull()?.message ?: "unknown"
                updateOfficialProgress(-1, msg)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.multiplayer_official_failed, msg),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val target = prepared.getOrThrow()
            updateOfficialProgress(95, getString(R.string.multiplayer_official_launching))
            val launch = AppContainer.gameRuntime.launch(
                requireContext(),
                target.versionId,
                account,
                serverAddress = target.server.serverAddress
            )
            officialJoinInProgress = false
            end?.buttonJoinOfficialServer?.isEnabled = true
            if (launch.isFailure) {
                val err = launch.exceptionOrNull()
                if (err is kotlinx.coroutines.CancellationException) {
                    updateOfficialProgress(-1, getString(R.string.legacy_gl4es_cancel))
                    return@launch
                }
                val msg = err?.message ?: "unknown"
                updateOfficialProgress(-1, msg)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.multiplayer_official_failed, msg),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                updateOfficialProgress(100, target.server.serverAddress)
            }
        }
    }

    private fun updateOfficialProgress(percent: Int, message: String) {
        val b = _binding ?: return
        b.panelOfficialProgress.isVisible = true
        b.textOfficialServerStatus.text = message
        if (percent < 0) {
            b.progressOfficialServer.isIndeterminate = true
        } else {
            b.progressOfficialServer.isIndeterminate = false
            b.progressOfficialServer.progress = percent.coerceIn(0, 100)
        }
    }

    private fun joinRoom(roomCode: String? = null) {
        val code = roomCode?.trim().orEmpty()
            .ifBlank { binding.inputRoomCode.text?.toString().orEmpty().trim() }
        if (code.isEmpty()) {
            toast(R.string.multiplayer_need_room_code)
            return
        }
        if (AppContainer.multiplayerAuth.current() == null) {
            toast(R.string.multiplayer_need_login_first)
            selectAccountTab()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            binding.buttonJoinRoom.isEnabled = false
            binding.buttonJoinPublicRoom.isEnabled = false
            val result = AppContainer.multiplayerAuth.joinRoomCode(code)
            val ui = _binding ?: return@launch
            ui.buttonJoinRoom.isEnabled = true
            ui.buttonJoinPublicRoom.isEnabled = true
            if (result.isSuccess) {
                selectedPublicRoom = null
                roomsLeftMode = RoomsLeftMode.BROWSE
                clearHostDepsCache()
                renderRoomsPanel()
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

    private fun clearHostDepsCache() {
        cachedHostDepsRoomCode = null
        cachedHostDeps = null
        hostDepsJob?.cancel()
        hostDepsJob = null
        hostDepsDownloadInProgress = false
    }

    private fun refreshHostDepsUi() {
        val b = _binding ?: return
        val lobby = AppContainer.multiplayerAuth.activeLobby.value
        if (lobby == null) {
            b.buttonDownloadHostDeps.isVisible = false
            b.textHostDepsInfo.isVisible = false
            return
        }
        val roomCode = lobby.roomCode
        val cached = cachedHostDeps
        if (cached != null &&
            roomCode.equals(cachedHostDepsRoomCode, ignoreCase = true)
        ) {
            applyHostDepsButton(cached)
            return
        }
        b.buttonDownloadHostDeps.isVisible = false
        b.textHostDepsInfo.isVisible = false
        hostDepsJob?.cancel()
        hostDepsJob = viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = runCatching {
                RoomHostDependencyInstaller.resolveSnapshot(roomCode)
            }.getOrDefault(RoomDependencySnapshot())
            if (_binding == null) return@launch
            if (!roomCode.equals(
                    AppContainer.multiplayerAuth.activeLobby.value?.roomCode,
                    ignoreCase = true
                )
            ) {
                return@launch
            }
            cachedHostDepsRoomCode = roomCode
            cachedHostDeps = snapshot
            applyHostDepsButton(snapshot)
        }
    }

    private fun applyHostDepsButton(snapshot: RoomDependencySnapshot) {
        val b = _binding ?: return
        val hasContent = RoomHostDependencyInstaller.hasDownloadableContent(snapshot)
        b.buttonDownloadHostDeps.isVisible = hasContent
        b.buttonDownloadHostDeps.isEnabled = hasContent && !hostDepsDownloadInProgress
        b.textHostDepsInfo.isVisible = hasContent
        if (!hasContent) return

        val version = snapshot.gameVersion?.ifBlank { null } ?: "未知版本"
        val loader = snapshot.loader?.ifBlank { null } ?: "原版"
        val summary = buildString {
            append(version)
            append(" · ")
            append(loader)
            if (snapshot.mods.isNotEmpty()) {
                append(" · ")
                append(snapshot.mods.size)
                append(" 个模组")
            } else if (!snapshot.modpackUrl.isNullOrBlank()) {
                append(" · 整合包")
            }
        }
        b.textHostDepsInfo.text = getString(R.string.multiplayer_host_deps_summary, summary)

        if (hostDepsDownloadInProgress) {
            b.buttonDownloadHostDeps.setText(R.string.multiplayer_download_host_deps_busy)
            return
        }
        val exact = RoomHostDependencyInstaller.findExactMatchVersion(snapshot)
        b.buttonDownloadHostDeps.setText(
            if (exact != null) R.string.multiplayer_download_host_deps_launch
            else R.string.multiplayer_download_host_deps
        )
    }

    private fun downloadHostDependencies() {
        val lobby = AppContainer.multiplayerAuth.activeLobby.value
        if (lobby == null) {
            toast(R.string.multiplayer_download_host_deps_none)
            return
        }
        if (hostDepsDownloadInProgress) return
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = cachedHostDeps?.takeIf {
                lobby.roomCode.equals(cachedHostDepsRoomCode, ignoreCase = true)
            } ?: RoomHostDependencyInstaller.resolveSnapshot(lobby.roomCode).also {
                cachedHostDepsRoomCode = lobby.roomCode
                cachedHostDeps = it
            }
            if (!RoomHostDependencyInstaller.hasDownloadableContent(snapshot)) {
                toast(R.string.multiplayer_download_host_deps_none)
                applyHostDepsButton(snapshot)
                return@launch
            }
            val exact = RoomHostDependencyInstaller.findExactMatchVersion(snapshot)
            if (exact != null) {
                RoomSessionTracker.track(exact.id)
                launchRoomSessionVersion(exact.id)
                applyHostDepsButton(snapshot)
                return@launch
            }

            val confirmed = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.multiplayer_download_host_deps)
                        .setMessage(
                            getString(
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
            }
            if (!confirmed) return@launch

            hostDepsDownloadInProgress = true
            applyHostDepsButton(snapshot)
            val result = runCatching {
                RoomHostDependencyInstaller.downloadAndCreate(snapshot) { status ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        val ui = _binding ?: return@launch
                        ui.buttonDownloadHostDeps.text = status.take(28)
                        ui.textHostDepsInfo.text = status
                        ui.textHostDepsInfo.isVisible = true
                    }
                }
            }
            hostDepsDownloadInProgress = false
            if (result.isSuccess) {
                val ok = result.getOrThrow()
                RoomSessionTracker.track(ok.versionId)
                val launchNow = withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.multiplayer_download_host_deps_ready_title)
                            .setMessage(
                                getString(
                                    R.string.multiplayer_download_host_deps_ready_message,
                                    ok.message
                                )
                            )
                            .setPositiveButton(R.string.multiplayer_download_host_deps_launch_now) { _, _ ->
                                if (cont.isActive) cont.resume(true)
                            }
                            .setNegativeButton(R.string.multiplayer_download_host_deps_later) { _, _ ->
                                if (cont.isActive) cont.resume(false)
                            }
                            .setOnCancelListener {
                                if (cont.isActive) cont.resume(false)
                            }
                            .show()
                    }
                }
                if (launchNow) {
                    launchRoomSessionVersion(ok.versionId)
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.multiplayer_download_host_deps_ok, ok.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                val err = result.exceptionOrNull()
                if (err !is CancellationException) {
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.multiplayer_download_host_deps_failed,
                            err?.message ?: "unknown"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            applyHostDepsButton(snapshot)
        }
    }

    private fun leaveRoomWithCleanup() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tracked = RoomSessionTracker.versionId
            AppContainer.multiplayerAuth.leaveActiveRoom()
            clearHostDepsCache()
            selectedPublicRoom = null
            roomsLeftMode = RoomsLeftMode.BROWSE
            renderRoomsPanel()
            if (!tracked.isNullOrBlank()) {
                val delete = withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.multiplayer_delete_room_version_title)
                            .setMessage(
                                getString(R.string.multiplayer_delete_room_version_message, tracked)
                            )
                            .setPositiveButton(R.string.multiplayer_delete_room_version_yes) { _, _ ->
                                if (cont.isActive) cont.resume(true)
                            }
                            .setNegativeButton(R.string.multiplayer_delete_room_version_keep) { _, _ ->
                                if (cont.isActive) cont.resume(false)
                            }
                            .setOnCancelListener {
                                if (cont.isActive) cont.resume(false)
                            }
                            .show()
                    }
                }
                if (delete) {
                    val deleted = withContext(Dispatchers.IO) {
                        AppContainer.repository.deleteInstalledVersion(tracked)
                    }
                    RoomSessionTracker.clear()
                    if (deleted.isSuccess) {
                        toast(R.string.multiplayer_delete_room_version_ok)
                    } else {
                        toast(R.string.multiplayer_delete_room_version_failed)
                    }
                } else {
                    RoomSessionTracker.clear()
                }
            } else {
                RoomSessionTracker.clear()
            }
        }
    }

    private fun launchRoomSessionVersion(versionId: String, forceDirectConnect: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            AppContainer.repository.selectVersion(versionId)
            RoomSessionTracker.track(versionId)
            val account = prepareLaunchAccount() ?: return@launch
            val server = if (forceDirectConnect) {
                AppContainer.multiplayerAuth.directConnectAddress.value
            } else {
                null
            }
            Toast.makeText(
                ctx,
                if (forceDirectConnect && !server.isNullOrBlank()) {
                    getString(R.string.multiplayer_download_host_deps_ok, "直连备用启动：$versionId")
                } else {
                    getString(
                        R.string.multiplayer_download_host_deps_ok,
                        getString(R.string.multiplayer_lan_join_hint)
                    )
                },
                Toast.LENGTH_LONG
            ).show()
            val result = AppContainer.gameRuntime.launch(
                context = ctx,
                versionId = versionId,
                account = account,
                serverAddress = server
            )
            if (result.isFailure) {
                val err = result.exceptionOrNull()
                if (err is CancellationException) return@launch
                Toast.makeText(
                    ctx,
                    getString(
                        R.string.multiplayer_launch_room_version_failed,
                        err?.message ?: "unknown"
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Explicit backup path: inject 127.0.0.1 tunnel via --server.
     * Prefer LAN list; only use when the list does not appear.
     */
    private fun launchDirectBackup() {
        val server = AppContainer.multiplayerAuth.directConnectAddress.value
        if (server.isNullOrBlank()) {
            toast(R.string.multiplayer_need_room_code)
            return
        }
        val tracked = RoomSessionTracker.versionId
        val selected = AppContainer.repository.session.value.selectedVersionId
        val versionId = tracked?.takeIf { it.isNotBlank() }
            ?: selected?.takeIf { it.isNotBlank() }
        if (versionId.isNullOrBlank()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.multiplayer_lan_join_hint),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        launchRoomSessionVersion(versionId, forceDirectConnect = true)
    }

    private suspend fun prepareLaunchAccount(): LauncherAccount? {
        var account = AppContainer.repository.selectedAccount()
            ?: LauncherAccount(
                id = "offline-default",
                name = "Player",
                type = AccountType.OFFLINE,
                selected = true
            )
        val joiningRoom = AppContainer.multiplayerAuth.activeLobby.value != null
        if (joiningRoom && account.type == AccountType.OFFLINE) {
            Toast.makeText(
                requireContext(),
                R.string.multiplayer_offline_join_hint,
                Toast.LENGTH_LONG
            ).show()
        }
        if (account.type == AccountType.MICROSOFT) {
            val refreshed = MicrosoftAuthService.ensureSession(
                account,
                forceRefresh = joiningRoom
            )
            if (refreshed.isFailure) {
                val err = refreshed.exceptionOrNull()
                val summary = getString(
                    R.string.accounts_ms_refresh_failed,
                    err?.message ?: "请重新登录"
                )
                val log = MicrosoftAuthLogger.lastReport
                    ?: "（无详细日志）\n${err?.message}"
                MicrosoftAuthErrorDialog.show(requireContext(), summary, log)
                return null
            }
            account = refreshed.getOrThrow()
            AppContainer.repository.upsertMicrosoftAccount(account)
        }
        return account
    }

    private fun refreshRooms() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ui = _binding ?: return@launch
            if (ui.panelRoomsPublicList.isVisible) {
                ui.textPublicRoomsEmpty.isVisible = true
                ui.textPublicRoomsEmpty.text = getString(R.string.multiplayer_loading)
            }
            val result = AppContainer.multiplayerAuth.listPublicRooms()
            val rooms = result.getOrNull().orEmpty()
            cachedPublicRooms = rooms
            val bound = _binding ?: return@launch
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message
                    ?: getString(R.string.multiplayer_rooms_load_failed)
                bound.textStatus.text = msg
                if (bound.panelRoomsPublicList.isVisible) {
                    bound.textPublicRoomsEmpty.text = msg
                    bound.textPublicRoomsEmpty.isVisible = true
                    bound.recyclerPublicRoomsGrid.isVisible = false
                }
            } else if (bound.panelRoomsPublicList.isVisible) {
                applyPublicRoomFilter()
            }
        }
    }

    private fun refreshFriends() {
        if (AppContainer.multiplayerAuth.current() == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val start = _binding ?: return@launch
            start.textFriendsEmpty.isVisible = true
            start.textFriendsEmpty.text = getString(R.string.multiplayer_loading)
            AppContainer.multiplayerAuth.refreshProfile()
            AppContainer.multiplayerAuth.bumpPresence(
                isInRoom = AppContainer.multiplayerAuth.activeLobby.value != null,
                roomCode = AppContainer.multiplayerAuth.activeLobby.value?.roomCode
            )
            val friendsResult = AppContainer.multiplayerAuth.loadFriends()
            val dash = friendsResult.getOrNull()
            val ui = _binding ?: return@launch

            if (friendsResult.isFailure) {
                val msg = friendsResult.exceptionOrNull()?.message
                    ?: getString(R.string.multiplayer_friends_load_failed)
                ui.textStatus.text = msg
                ui.textFriendsEmpty.text = msg
                ui.textFriendsEmpty.isVisible = true
                friendsAdapter.submit(emptyList())
                return@launch
            }

            friendsAdapter.submit(
                dash?.friends.orEmpty().map { f ->
                    MultiplayerListItem(
                        id = f.userId,
                        name = f.username,
                        meta = friendStatus(f),
                        avatarUrl = f.avatarUrl,
                        frameId = f.selectedFrameId,
                        primaryLabel = friendPrimaryAction(f),
                        secondaryLabel = friendSecondaryAction(f),
                        payload = f
                    )
                }
            )
            ui.textFriendsEmpty.isVisible = dash?.friends.isNullOrEmpty()
            if (dash?.friends.isNullOrEmpty()) {
                ui.textFriendsEmpty.text = getString(R.string.multiplayer_friends_empty)
            }

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
            ui.textRequestsEmpty.isVisible = dash?.incomingRequests.isNullOrEmpty()

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
            ui.textInvitesEmpty.isVisible = dash?.pendingRoomInvites.isNullOrEmpty()

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
            ui.textBlockedEmpty.isVisible = dash?.blockedUsers.isNullOrEmpty()

            ui.textStatus.text = getString(
                R.string.multiplayer_refreshed,
                AppContainer.multiplayerAuth.current()?.user?.username ?: ""
            )
            AppContainer.multiplayerAuth.current()?.let { renderSession(it) }
        }
    }

    private fun refreshRewards() {
        if (AppContainer.multiplayerAuth.current() == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val start = _binding ?: return@launch
            start.textRewards.text = getString(R.string.multiplayer_rewards_loading)
            start.buttonCheckIn.isEnabled = false
            val result = AppContainer.multiplayerAuth.loadRewardProfile()
            val ui = _binding ?: return@launch
            if (result.isFailure) {
                ui.textRewards.text = getString(R.string.multiplayer_rewards_load_failed)
            }
            ui.buttonCheckIn.isEnabled = true
        }
    }

    private fun renderRewards(profile: RewardProfile?) {
        val binding = _binding ?: return
        if (profile == null) {
            binding.buttonCheckIn.text = getString(R.string.multiplayer_checkin)
            binding.buttonCheckIn.isEnabled = AppContainer.multiplayerAuth.current() != null
            return
        }
        val checkedIn = RewardProfileHelper.hasCheckedInToday(profile)
        val status = if (checkedIn) {
            getString(R.string.multiplayer_rewards_checked_in)
        } else {
            getString(R.string.multiplayer_rewards_not_checked_in)
        }
        binding.textRewards.text = getString(
            R.string.multiplayer_rewards_summary,
            RewardProfileHelper.rankLabel(profile),
            profile.gold,
            status
        )
        binding.buttonCheckIn.text = if (checkedIn) {
            getString(R.string.multiplayer_checkin_done)
        } else {
            getString(R.string.multiplayer_checkin)
        }
        binding.buttonCheckIn.isEnabled = !checkedIn
        applySessionFrame(profile.selectedFrameId)
    }

    private fun performCheckIn() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.buttonCheckIn.isEnabled = false
            val result = AppContainer.multiplayerAuth.checkIn()
            val claim = result.getOrNull()
            val message = when {
                result.isFailure -> result.exceptionOrNull()?.message ?: "签到失败"
                claim?.alreadyClaimed == true -> getString(R.string.multiplayer_checkin_already)
                claim?.ok == true -> claim.message ?: getString(R.string.multiplayer_checkin_ok)
                else -> claim?.message ?: getString(R.string.multiplayer_checkin_ok)
            }
            if (!isAdded) return@launch
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            if (result.isSuccess) {
                refreshRewards()
                renderSession(AppContainer.multiplayerAuth.current())
            } else {
                val ui = _binding ?: return@launch
                ui.buttonCheckIn.isEnabled = true
            }
        }
    }

    private fun showFramePicker() {
        val profile = rewardProfile
        if (profile == null) {
            toast(R.string.multiplayer_rewards_loading)
            refreshRewards()
            return
        }
        val frameIds = buildList {
            add("none")
            addAll(profile.ownedFrameIds)
            profile.selectedFrameId?.takeIf { it.isNotBlank() && it != "none" }?.let { add(it) }
        }.distinct()
        val labels = frameIds.map { frameLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.multiplayer_pick_frame)
            .setItems(labels) { _, which ->
                val frameId = frameIds[which]
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = AppContainer.multiplayerAuth.selectFrame(frameId)
                    if (result.isSuccess) {
                        toast(R.string.multiplayer_frame_updated)
                        renderSession(AppContainer.multiplayerAuth.current())
                        refreshRewards()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            result.exceptionOrNull()?.message ?: "failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun frameLabel(frameId: String): String {
        val res = when (frameId.lowercase()) {
            "none" -> R.string.multiplayer_frame_none
            "wood" -> R.string.multiplayer_frame_wood
            "iron" -> R.string.multiplayer_frame_iron
            "gold" -> R.string.multiplayer_frame_gold
            "diamond" -> R.string.multiplayer_frame_diamond
            "nether" -> R.string.multiplayer_frame_nether
            "end" -> R.string.multiplayer_frame_end
            "peak" -> R.string.multiplayer_frame_peak
            "cyan" -> R.string.multiplayer_frame_cyan
            "rose" -> R.string.multiplayer_frame_rose
            "aurora" -> R.string.multiplayer_frame_aurora
            "miner" -> R.string.multiplayer_frame_miner
            else -> null
        }
        return if (res != null) getString(res) else frameId
    }

    private fun applySessionFrame(frameId: String?) {
        val binding = _binding ?: return
        binding.imageSessionFrame.applyBooxinFrame(frameId)
    }

    private fun refreshLobby(resetPage: Boolean = false) {
        if (AppContainer.multiplayerAuth.current() == null) return
        if (lobbyLoading) return
        if (resetPage) lobbyPage = 1
        viewLifecycleOwner.lifecycleScope.launch {
            lobbyLoading = true
            setLobbyPaginationEnabled(false)
            try {
                var lobbyResult = AppContainer.multiplayerAuth.loadLobby(lobbyPage)
                var page = lobbyResult.getOrNull()
                if (page != null && page.totalPages > 0 && lobbyPage > page.totalPages) {
                    lobbyPage = page.totalPages
                    lobbyResult = AppContainer.multiplayerAuth.loadLobby(lobbyPage)
                    page = lobbyResult.getOrNull()
                }
                val ui = _binding ?: return@launch
                lastLobbyPage = page
                val users = page?.users.orEmpty()
                lobbyAdapter.submit(
                    users.map { u ->
                        MultiplayerListItem(
                            id = u.id,
                            name = u.username,
                            meta = statusLine(u.isOnline, u.isFriend),
                            avatarUrl = u.avatarUrl,
                            frameId = u.selectedFrameId,
                            primaryLabel = when {
                                u.isFriend -> null
                                u.hasPendingOutgoingRequest -> getString(R.string.multiplayer_request_sent)
                                u.hasPendingIncomingRequest -> getString(R.string.multiplayer_accept)
                                else -> getString(R.string.multiplayer_add_friend)
                            },
                            payload = u
                        )
                    }
                )
                ui.textLobbyEmpty.isVisible = lobbyResult.isSuccess && users.isEmpty()
                if (lobbyResult.isFailure) {
                    ui.textStatus.text = lobbyResult.exceptionOrNull()?.message
                    lastLobbyPage = null
                    updateLobbyPagination(null)
                } else {
                    updateLobbyPagination(page)
                    if (users.isNotEmpty()) {
                        ui.recyclerLobby.scrollToPosition(0)
                    }
                }
            } finally {
                lobbyLoading = false
                updateLobbyPagination(lastLobbyPage)
            }
        }
    }

    private fun updateLobbyPagination(page: LobbyPage?) {
        val b = _binding ?: return
        if (page == null || page.totalCount <= 0) {
            b.lobbyPaginationBar.isVisible = false
            return
        }
        b.lobbyPaginationBar.isVisible = true
        b.textLobbyPageInfo.text = getString(
            R.string.multiplayer_lobby_page_info,
            page.page,
            page.totalPages.coerceAtLeast(1),
            page.totalCount
        )
        setLobbyPaginationEnabled(!lobbyLoading)
    }

    private fun setLobbyPaginationEnabled(enabled: Boolean) {
        val b = _binding ?: return
        val page = lastLobbyPage
        b.buttonLobbyPrevPage.isEnabled = enabled && page?.hasPrevious == true
        b.buttonLobbyNextPage.isEnabled = enabled && page?.hasNext == true
    }

    /** Friend in a room → Join; else if I'm in a room → Invite; else Chat. */
    private fun friendPrimaryAction(friend: BooxinFriend): String =
        when {
            friend.isInRoom -> getString(R.string.multiplayer_join_friend_room)
            AppContainer.multiplayerAuth.activeLobby.value != null ->
                getString(R.string.multiplayer_invite_friend)
            else -> getString(R.string.multiplayer_chat)
        }

    private fun friendSecondaryAction(friend: BooxinFriend): String =
        when {
            friend.isInRoom -> getString(R.string.multiplayer_chat)
            AppContainer.multiplayerAuth.activeLobby.value != null ->
                getString(R.string.multiplayer_chat)
            else -> getString(R.string.multiplayer_remove_friend)
        }

    private fun joinFriendRoom(friend: BooxinFriend) {
        val code = friend.roomCode?.trim().orEmpty()
        if (code.isBlank()) {
            Toast.makeText(
                requireContext(),
                R.string.multiplayer_friend_room_code_missing,
                Toast.LENGTH_LONG
            ).show()
            refreshFriends()
            return
        }
        val b = _binding ?: return
        b.inputRoomCode.setText(code)
        joinRoom()
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
            val ui = _binding ?: return@launch
            conversationsAdapter.submit(items)
            ui.textConversationsEmpty.isVisible = items.isEmpty()
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
            val ui = _binding ?: return@launch
            if (result.isFailure) {
                ui.textStatus.text = result.exceptionOrNull()?.message
                return@launch
            }
            searchAdapter.submit(
                result.getOrThrow().map { u ->
                    MultiplayerListItem(
                        id = u.id,
                        name = u.username,
                        meta = statusLine(u.isOnline, u.isFriend),
                        avatarUrl = u.avatarUrl,
                        frameId = u.selectedFrameId,
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

    private fun openLobbyChat() {
        if (AppContainer.multiplayerAuth.current() == null) {
            Toast.makeText(requireContext(), R.string.multiplayer_lobby_chat_need_login, Toast.LENGTH_SHORT)
                .show()
            return
        }
        startActivity(Intent(requireContext(), LobbyChatActivity::class.java))
    }

    private fun openUserDetail(userId: String) {
        if (userId.isBlank()) return
        if (AppContainer.multiplayerAuth.current() == null) {
            Toast.makeText(requireContext(), R.string.multiplayer_need_login_first, Toast.LENGTH_SHORT)
                .show()
            return
        }
        UserDetailDialog.show(
            context = requireContext(),
            lifecycleOwner = viewLifecycleOwner,
            userId = userId,
            onChat = { peerId, peerName -> openChat(peerId, peerName) },
            onChanged = {
                refreshFriends()
                refreshLobby()
            }
        )
    }

    private fun ensureDmNotificationPermission() {
        if (askedDmNotificationPermission) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        askedDmNotificationPermission = true
        requestDmNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun addFriend(userId: String, username: String, onSuccess: (() -> Unit)? = null) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = AppContainer.multiplayerAuth.sendFriendRequest(userId, username)
            Toast.makeText(
                requireContext(),
                result.getOrElse { it.message ?: "failed" },
                Toast.LENGTH_SHORT
            ).show()
            if (result.isSuccess) {
                refreshFriends()
                onSuccess?.invoke()
            }
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
        if (friend.isInRoom) {
            append(" · ")
            append(
                if (!friend.roomCode.isNullOrBlank()) {
                    getString(R.string.multiplayer_in_room, friend.roomCode)
                } else {
                    getString(R.string.multiplayer_in_room_no_code)
                }
            )
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
        binding.buttonAccountSettings.setOnClickListener {
            val open = !binding.panelAccountAdvanced.isVisible
            binding.panelAccountAdvanced.isVisible = open
            binding.buttonAccountSettings.text = getString(
                if (open) R.string.multiplayer_account_settings_hide
                else R.string.multiplayer_account_settings
            )
        }
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
                    val ui = _binding ?: return@launch
                    ui.inputCurrentPassword.setText("")
                    ui.inputNewPassword.setText("")
                }
                if (!isAdded) return@launch
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
        if (AppContainer.multiplayerAuth.activeLobby.value == null) {
            toast(R.string.multiplayer_invite_need_host)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = AppContainer.multiplayerAuth.sendRoomInvite(friend.userId)
            if (!isAdded) return@launch
            if (result.isSuccess) {
                Toast.makeText(
                    requireContext(),
                    result.getOrNull() ?: getString(R.string.multiplayer_invite_sent),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    requireContext(),
                    result.exceptionOrNull()?.message ?: "failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Consume PC/notification/deep-link one-click join requests. */
    private fun consumePendingRoomJoin() {
        val pending = RoomInviteCoordinator.consumePendingJoin() ?: return
        val b = _binding ?: return
        if (AppContainer.multiplayerAuth.current() == null) {
            RoomInviteCoordinator.requestJoin(pending.roomCode, pending.inviteId)
            toast(R.string.multiplayer_need_login_first)
            selectAccountTab()
            return
        }
        if (AppContainer.multiplayerAuth.activeLobby.value != null) {
            toast(R.string.multiplayer_room_invite_busy)
            pending.inviteId?.let { id ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppContainer.multiplayerAuth.dismissInvite(id)
                }
            }
            return
        }
        b.inputRoomCode.setText(pending.roomCode)
        // Show invites sub-tab so the list refreshes after accept.
        runCatching {
            binding.tabMultiplayer.getTabAt(0)?.select()
            binding.tabFriendsSub.getTabAt(2)?.select()
        }
        pending.inviteId?.let { id ->
            viewLifecycleOwner.lifecycleScope.launch {
                AppContainer.multiplayerAuth.dismissInvite(id)
            }
        }
        joinRoom()
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
        val binding = _binding ?: return
        val loggedIn = session != null
        binding.panelLogin.isVisible = !loggedIn
        binding.panelSession.isVisible = loggedIn
        if (!loggedIn) {
            binding.panelAccountAdvanced.isVisible = false
            binding.buttonAccountSettings.text = getString(R.string.multiplayer_account_settings)
        }
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
            applySessionFrame(
                rewardProfile?.selectedFrameId?.takeIf { it.isNotBlank() }
                    ?: user.selectedFrameId
            )
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
        hostDepsJob?.cancel()
        hostDepsJob = null
        clearHostDepsCache()
        super.onDestroyView()
        _binding = null
    }
}
