package com.booxin.launcher.core.multiplayer

import android.content.Context
import com.booxin.launcher.core.diag.DiagEventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-game (:game process) lobby controller — create / join / members.
 * Loads Booxin session from disk so it works outside the main-process AppContainer.
 */
class InGameLobbyController(
    private val context: Context
) {
    private val store = MultiplayerSessionStore(context)
    private val roomApi = BooxinRoomApi()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _members = MutableStateFlow<List<RoomMember>>(emptyList())
    val members: StateFlow<List<RoomMember>> = _members.asStateFlow()

    private val _lobby = MutableStateFlow<TerracottaLobbyInfo?>(null)
    val lobby: StateFlow<TerracottaLobbyInfo?> = _lobby.asStateFlow()

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()

    private val _directConnect = MutableStateFlow<String?>(null)
    val directConnect: StateFlow<String?> = _directConnect.asStateFlow()

    private var hostCoordinator: RoomHostCoordinator? = null
    private var joinCoordinator: RoomJoinCoordinator? = null

    fun loadSession(): BooxinAuthSession? = store.load()

    fun resolvePlayerName(fallback: String): String =
        fallback.trim().ifBlank { "Player" }

    suspend fun createRoom(
        minecraftPort: Int,
        playerNameHint: String,
        isPublic: Boolean = true,
        roomName: String? = null
    ): Result<RoomHostResult> {
        val session = loadSession()
            ?: return Result.failure(IllegalStateException("请先在联机页登录账号"))
        return runCatching {
            leaveInternal()
            val playerName = resolvePlayerName(playerNameHint)
            val coordinator = RoomHostCoordinator(
                context = context,
                roomApi = roomApi,
                onStatus = { _status.value = it },
                onMembersChanged = { _members.value = it }
            )
            hostCoordinator = coordinator
            val result = coordinator.create(
                minecraftPort = minecraftPort,
                playerName = playerName,
                hostId = session.user.id,
                isPublic = isPublic,
                roomName = roomName
            )
            _lobby.value = result.lobby
            _isHost.value = true
            _directConnect.value = null
            _members.value = result.members
            roomApi.joinRoom(session, result.lobby.roomCode, playerName)
            result
        }.onFailure { err ->
            DiagEventLog.e(TAG, "createRoom failed", err)
            _status.value = "创建失败：${err.message}"
            leaveInternal()
        }
    }

    suspend fun joinRoom(roomCode: String): Result<RoomJoinResult> {
        val session = loadSession()
            ?: return Result.failure(IllegalStateException("请先在联机页登录账号"))
        return runCatching {
            leaveInternal()
            val playerName = resolvePlayerName(session.user.username)
            val coordinator = RoomJoinCoordinator(
                context = context,
                onStatus = { _status.value = it },
                onMembersChanged = { _members.value = it }
            )
            joinCoordinator = coordinator
            val result = coordinator.join(roomCode.trim(), playerName)
            roomApi.joinRoom(session, result.lobby.roomCode, playerName)
            _lobby.value = result.lobby
            _isHost.value = false
            _directConnect.value = result.directConnectAddress
            _members.value = result.members
            _status.value =
                "已加入 · 直连 ${result.directConnectAddress} · ${result.members.size} 人"
            result
        }.onFailure { err ->
            DiagEventLog.e(TAG, "joinRoom failed", err)
            _status.value = "加入失败：${err.message}"
            leaveInternal()
        }
    }

    suspend fun leave() {
        val lobby = _lobby.value
        val session = loadSession()
        if (lobby != null && session != null) {
            runCatching { roomApi.leaveRoom(session, lobby.roomCode) }
        }
        if (_isHost.value) {
            hostCoordinator?.leave()
        } else {
            joinCoordinator?.leave()
        }
        hostCoordinator = null
        joinCoordinator = null
        _lobby.value = null
        _isHost.value = false
        _directConnect.value = null
        _members.value = emptyList()
        _status.value = "已离开房间"
    }

    fun refreshMembersFromHost() {
        if (_isHost.value) {
            _members.value = hostCoordinator?.currentMembers().orEmpty()
        }
    }

    private fun leaveInternal() {
        try {
            hostCoordinator?.stopLocal()
        } catch (_: Throwable) {
        }
        try {
            joinCoordinator?.leave()
        } catch (_: Throwable) {
        }
        hostCoordinator = null
        joinCoordinator = null
        _lobby.value = null
        _isHost.value = false
        _directConnect.value = null
        _members.value = emptyList()
    }

    companion object {
        private const val TAG = "InGameLobby"
    }
}
