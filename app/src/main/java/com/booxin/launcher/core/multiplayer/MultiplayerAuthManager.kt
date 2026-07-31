package com.booxin.launcher.core.multiplayer

import com.booxin.launcher.BooxinApp
import com.booxin.launcher.core.diag.DiagEventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MultiplayerAuthManager(
    private val store: MultiplayerSessionStore,
    private val api: BooxinMultiplayerApi = BooxinMultiplayerApi(),
    private val roomApi: BooxinRoomApi = BooxinRoomApi()
) {
    private val _session = MutableStateFlow(store.load())
    val session: StateFlow<BooxinAuthSession?> = _session.asStateFlow()

    private val _joinStatus = MutableStateFlow<String?>(null)
    val joinStatus: StateFlow<String?> = _joinStatus.asStateFlow()

    private val _activeLobby = MutableStateFlow<TerracottaLobbyInfo?>(null)
    val activeLobby: StateFlow<TerracottaLobbyInfo?> = _activeLobby.asStateFlow()

    private val _directConnect = MutableStateFlow<String?>(null)
    val directConnectAddress: StateFlow<String?> = _directConnect.asStateFlow()

    private val _roomMembers = MutableStateFlow<List<RoomMember>>(emptyList())
    val roomMembers: StateFlow<List<RoomMember>> = _roomMembers.asStateFlow()

    private val _rewardProfile = MutableStateFlow<RewardProfile?>(null)
    val rewardProfile: StateFlow<RewardProfile?> = _rewardProfile.asStateFlow()

    @Volatile
    private var joinCoordinator: RoomJoinCoordinator? = null

    fun current(): BooxinAuthSession? = _session.value
    val rooms: BooxinRoomApi get() = roomApi

    private fun requireSession(): BooxinAuthSession =
        _session.value ?: error("未登录联机账号")

    private fun remember(session: BooxinAuthSession) {
        store.save(session)
        _session.value = session
    }

    suspend fun login(username: String, password: String): Result<BooxinAuthSession> {
        val result = api.login(username, password)
        result.onSuccess { remember(it) }
        return result
    }

    suspend fun loginWithEmail(email: String, code: String): Result<BooxinAuthSession> {
        val result = api.loginWithEmail(email, code)
        result.onSuccess { remember(it) }
        return result
    }

    suspend fun register(
        username: String,
        password: String,
        email: String,
        emailCode: String
    ): Result<BooxinAuthSession> {
        val result = api.register(username, password, email, emailCode)
        result.onSuccess { remember(it) }
        return result
    }

    suspend fun sendRegisterCode(email: String) = api.sendRegisterCode(email)
    suspend fun sendEmailLoginCode(email: String) = api.sendEmailLoginCode(email)
    suspend fun sendPasswordResetCode(email: String) = api.sendPasswordResetCode(email)
    suspend fun resetPassword(email: String, code: String, newPassword: String) =
        api.resetPassword(email, code, newPassword)

    suspend fun refreshProfile(): Result<BooxinUser> {
        val session = _session.value
            ?: return Result.failure(IllegalStateException("未登录联机账号"))
        val result = api.fetchMe(session)
        result.onSuccess { remember(session.copy(user = it)) }
        return result
    }

    suspend fun loadFriends() = runCatching { api.fetchFriends(requireSession()).getOrThrow() }
    suspend fun loadLobby() = runCatching { api.fetchLobby(requireSession()).getOrThrow() }
    suspend fun searchUsers(query: String) =
        runCatching { api.searchUsers(requireSession(), query).getOrThrow() }

    suspend fun sendFriendRequest(targetUserId: String? = null, username: String? = null) =
        runCatching { api.sendFriendRequest(requireSession(), targetUserId, username).getOrThrow() }

    suspend fun acceptFriendRequest(requestId: String) =
        runCatching { api.acceptFriendRequest(requireSession(), requestId).getOrThrow() }

    suspend fun rejectFriendRequest(requestId: String) =
        runCatching { api.rejectFriendRequest(requireSession(), requestId).getOrThrow() }

    suspend fun removeFriend(friendUserId: String) =
        runCatching { api.removeFriend(requireSession(), friendUserId).getOrThrow() }

    suspend fun blockUser(userId: String) =
        runCatching { api.blockUser(requireSession(), userId).getOrThrow() }

    suspend fun unblockUser(userId: String) =
        runCatching { api.unblockUser(requireSession(), userId).getOrThrow() }

    suspend fun dismissInvite(inviteId: String) =
        runCatching { api.dismissInvite(requireSession(), inviteId).getOrThrow() }

    suspend fun bumpPresence(isInRoom: Boolean = false, roomCode: String? = null) =
        runCatching {
            api.updatePresence(requireSession(), isInRoom = isInRoom, roomCode = roomCode).getOrThrow()
        }

    suspend fun listConversations() =
        runCatching { api.listConversations(requireSession()).getOrThrow() }

    suspend fun getMessages(peerUserId: String, afterId: Long = 0L) =
        runCatching { api.getMessages(requireSession(), peerUserId, afterId).getOrThrow() }

    suspend fun sendMessage(receiverId: String, body: String) =
        runCatching { api.sendMessage(requireSession(), receiverId, body).getOrThrow() }

    suspend fun markRead(peerUserId: String, upToMessageId: Long) =
        runCatching { api.markRead(requireSession(), peerUserId, upToMessageId).getOrThrow() }

    suspend fun recallMessage(messageId: Long) =
        runCatching { api.recallMessage(requireSession(), messageId).getOrThrow() }

    suspend fun updateSignature(signature: String): Result<BooxinUser> {
        val session = requireSession()
        val result = api.updateSignature(session, signature)
        result.onSuccess { remember(session.copy(user = it)) }
        return result
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) =
        runCatching { api.changePassword(requireSession(), currentPassword, newPassword).getOrThrow() }

    suspend fun sendBindEmailCode(email: String) =
        runCatching { api.sendBindEmailCode(requireSession(), email).getOrThrow() }

    suspend fun verifyBindEmail(email: String, code: String): Result<BooxinUser> {
        val session = requireSession()
        val result = api.verifyBindEmail(session, email, code)
        result.onSuccess { remember(session.copy(user = it)) }
        return result
    }

    suspend fun unbindEmail(): Result<BooxinUser> {
        val session = requireSession()
        val result = api.unbindEmail(session)
        result.onSuccess { remember(session.copy(user = it)) }
        return result
    }

    suspend fun uploadAvatar(bytes: ByteArray, mime: String): Result<BooxinUser> {
        val session = requireSession()
        val result = api.uploadAvatar(session, bytes, mime)
        result.onSuccess { remember(session.copy(user = it)) }
        return result
    }

    suspend fun deleteAvatar(): Result<BooxinUser> {
        val session = requireSession()
        val result = api.deleteAvatar(session)
        result.onSuccess { remember(session.copy(user = it)) }
        return result
    }

    suspend fun loadRewardProfile(): Result<RewardProfile> {
        val session = requireSession()
        return api.getRewardProfile(session).onSuccess { profile ->
            _rewardProfile.value = profile
            val frameId = profile.selectedFrameId
            if (!frameId.isNullOrBlank() && frameId != session.user.selectedFrameId) {
                remember(session.copy(user = session.user.copy(selectedFrameId = frameId)))
            }
        }
    }

    suspend fun checkIn(): Result<RewardClaimResult> {
        val session = requireSession()
        return api.checkIn(session).onSuccess { result ->
            result.profile?.let { profile ->
                _rewardProfile.value = profile
                remember(session.copy(user = session.user.copy(selectedFrameId = profile.selectedFrameId)))
            }
        }
    }

    suspend fun selectFrame(frameId: String): Result<RewardClaimResult> {
        val session = requireSession()
        return api.selectFrame(session, frameId).onSuccess { result ->
            result.profile?.let { profile ->
                _rewardProfile.value = profile
                remember(session.copy(user = session.user.copy(selectedFrameId = profile.selectedFrameId)))
            }
        }
    }

    suspend fun sendRoomInvite(friendUserId: String) =
        runCatching { api.sendRoomInvite(requireSession(), friendUserId).getOrThrow() }

    suspend fun listPublicRooms() = roomApi.listPublicRooms()

    /**
     * Join Booxin PC room (29-char code, no U/). Full EasyTier + Scaffolding + port-forward.
     */
    suspend fun joinRoomCode(rawCode: String): Result<RoomJoinResult> {
        val session = _session.value
            ?: return Result.failure(IllegalStateException("请先登录联机账号"))
        return runCatching {
            DiagEventLog.i(
                "MultiplayerAuth",
                "joinRoom start user=${session.user.username} codeLen=${rawCode.trim().length}"
            )
            val coordinator = RoomJoinCoordinator(
                context = BooxinApp.getAppContext(),
                onStatus = { msg ->
                    _joinStatus.value = msg
                    DiagEventLog.i("RoomJoin", msg)
                },
                onMembersChanged = { members -> _roomMembers.value = members }
            )
            joinCoordinator?.leave()
            joinCoordinator = coordinator

            // PC ResolveMultiplayerUserName: Scaffolding name = Minecraft account name,
            // not the Booxin social username (mismatch breaks offline join / roster).
            val playerName = resolveMinecraftPlayerName(session.user.username)
            DiagEventLog.i("MultiplayerAuth", "scaffolding playerName=$playerName")
            val result = coordinator.join(rawCode.trim(), playerName)
            roomApi.joinRoom(session, result.lobby.roomCode, playerName)
            bumpPresence(isInRoom = true, roomCode = result.lobby.roomCode)
            _activeLobby.value = result.lobby
            _directConnect.value = result.directConnectAddress
            _roomMembers.value = result.members
            _joinStatus.value =
                "已加入 · 直连 ${result.directConnectAddress} · 玩家 ${result.members.size}"
            DiagEventLog.i(
                "MultiplayerAuth",
                "joinRoom ok addr=${result.directConnectAddress} players=${result.members.size}"
            )
            result
        }.onFailure { err ->
            _joinStatus.value = "加入失败：${err.message}"
            DiagEventLog.e("MultiplayerAuth", "joinRoom failed", err)
            leaveActiveRoom()
        }
    }

    suspend fun leaveActiveRoom() {
        val lobby = _activeLobby.value
        val session = _session.value
        if (lobby != null && session != null) {
            runCatching { roomApi.leaveRoom(session, lobby.roomCode) }
        }
        joinCoordinator?.leave()
        joinCoordinator = null
        EasyTierSessionHolder.stop()
        bumpPresence(isInRoom = false, roomCode = null)
        _activeLobby.value = null
        _directConnect.value = null
        _roomMembers.value = emptyList()
        _joinStatus.value = "已离开房间"
    }

    fun logout() {
        joinCoordinator?.leave()
        joinCoordinator = null
        EasyTierSessionHolder.stop()
        store.clear()
        _session.value = null
        _activeLobby.value = null
        _directConnect.value = null
        _roomMembers.value = emptyList()
        _joinStatus.value = null
        _rewardProfile.value = null
    }

    /** Same rule as PC MainWindow.ResolveMultiplayerUserName(). */
    private fun resolveMinecraftPlayerName(fallback: String): String {
        val selected = runCatching {
            com.booxin.launcher.AppContainer.repository.selectedAccount()?.name?.trim()
        }.getOrNull()
        return selected?.takeIf { it.isNotBlank() } ?: fallback.trim().ifBlank { "Player" }
    }
}
