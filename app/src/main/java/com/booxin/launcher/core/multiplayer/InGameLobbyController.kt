package com.booxin.launcher.core.multiplayer

import android.content.Context
import com.booxin.launcher.core.diag.DiagEventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 游戏内大厅：创建 / 加入 / 成员。
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
    /** True when UI state was restored from launcher (no local EasyTier in :game). */
    private var adoptedFromLauncher = false

    fun loadSession(): BooxinAuthSession? = store.load()

    fun resolvePlayerName(fallback: String): String =
        fallback.trim().ifBlank { "Player" }

    /** Sync UI with a room joined/hosted in the launcher (main process). */
    fun restoreFromSharedSession(): Boolean {
        if (_lobby.value != null) return true
        val snap = ActiveRoomSessionStore.load(context) ?: return false
        _lobby.value = snap.toLobby()
        _isHost.value = snap.isHost
        _directConnect.value = snap.directConnect
        _members.value = snap.members
        _status.value = if (snap.isHost) {
            "已同步启动器房间（房主）"
        } else {
            "已同步启动器加入的房间"
        }
        adoptedFromLauncher = true
        return true
    }

    suspend fun createRoom(
        minecraftPort: Int,
        playerNameHint: String,
        isPublic: Boolean = true,
        roomName: String? = null,
        roomRemark: String? = null,
        modpackUrl: String? = null,
        versionId: String? = null,
        dependencyInfo: RoomHostDependencyInfo? = null
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
                onMembersChanged = { members ->
                    _members.value = members
                    ActiveRoomSessionStore.updateMembers(context, members)
                }
            )
            hostCoordinator = coordinator
            adoptedFromLauncher = false
            val displayName = roomName?.trim()?.ifBlank { null } ?: "${playerName}的房间"

            val deps = dependencyInfo ?: run {
                val scanId = versionId?.trim().orEmpty()
                if (scanId.isEmpty()) {
                    RoomHostDependencyInfo()
                } else {
                    _status.value = "正在识别房主依赖…"
                    RoomHostDependencyService().scanInstance(
                        versionId = scanId,
                        onProgress = { _status.value = it }
                    )
                }
            }

            val result = coordinator.create(
                minecraftPort = minecraftPort,
                playerName = playerName,
                hostId = session.user.id,
                isPublic = isPublic,
                roomName = displayName,
                roomRemark = roomRemark,
                gameVersion = deps.gameVersion.ifBlank { null },
                modpackUrl = modpackUrl,
                modpackGameVersion = deps.gameVersion.ifBlank { null },
                modpackLoader = deps.loader,
                roomMods = deps.mods.filter { it.hasDownloadSource }
            )
            _lobby.value = result.lobby
            _isHost.value = true
            _directConnect.value = null
            _members.value = result.members
            roomApi.joinRoom(session, result.lobby.roomCode, playerName)
            ActiveRoomSessionStore.saveFromLobby(
                context = context,
                lobby = result.lobby,
                isHost = true,
                members = result.members
            )
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
                onMembersChanged = { members ->
                    _members.value = members
                    ActiveRoomSessionStore.updateMembers(context, members)
                }
            )
            joinCoordinator = coordinator
            adoptedFromLauncher = false
            val result = coordinator.join(roomCode.trim(), playerName)
            roomApi.joinRoom(session, result.lobby.roomCode, playerName)
            _lobby.value = result.lobby
            _isHost.value = false
            _directConnect.value = result.directConnectAddress
            _members.value = result.members
            _status.value = RoomJoinCoordinator.JOIN_SUCCESS_STATUS
            ActiveRoomSessionStore.saveFromLobby(
                context = context,
                lobby = result.lobby,
                isHost = false,
                directConnect = result.directConnectAddress,
                members = result.members
            )
            LobbyTunnelService.start(context, result.directConnectAddress)
            DiagEventLog.i(
                TAG,
                "joinRoom ok addr=${result.directConnectAddress} " +
                    "lanBroadcast=${result.lanBroadcastStarted} players=${result.members.size}"
            )
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
        val wasHost = _isHost.value
        val adopted = adoptedFromLauncher
        val code = lobby?.roomCode
        if (lobby != null && session != null && !adopted) {
            runCatching { roomApi.leaveRoom(session, lobby.roomCode) }
        }
        LobbyTunnelService.stop(context)
        if (adopted) {
            // Tunnel lives in main process — ask it to tear down.
            ActiveRoomSessionStore.requestLeaveAcrossProcesses(context)
        } else if (wasHost) {
            hostCoordinator?.leaveQuietly()
        } else {
            joinCoordinator?.leave()
        }
        hostCoordinator = null
        joinCoordinator = null
        adoptedFromLauncher = false
        _lobby.value = null
        _isHost.value = false
        _directConnect.value = null
        _members.value = emptyList()
        _status.value = "已离开房间"
        ActiveRoomSessionStore.clear(context, code)
    }

    fun refreshMembersFromHost() {
        if (_isHost.value) {
            _members.value = hostCoordinator?.currentMembers().orEmpty()
        }
    }

    suspend fun refreshMembers() {
        if (adoptedFromLauncher) {
            ActiveRoomSessionStore.load(context)?.members?.let { _members.value = it }
            return
        }
        if (_isHost.value) {
            _members.value = hostCoordinator?.currentMembers().orEmpty()
            ActiveRoomSessionStore.updateMembers(context, _members.value)
        } else {
            joinCoordinator?.refreshMembers()
        }
    }

    suspend fun listPublicRooms() = roomApi.listPublicRooms()

    suspend fun getPublicRoom(roomCode: String) = roomApi.getRoom(roomCode)

    /**
     * Tear down local networking. Hosts also DELETE the directory entry
     * (same as PC LeaveLobby / Dispose) so public list does not keep zombies.
     */
    private suspend fun leaveInternal() {
        val wasHost = _isHost.value
        val code = _lobby.value?.roomCode
        LobbyTunnelService.stop(context)
        try {
            if (wasHost) {
                hostCoordinator?.leaveQuietly()
            } else {
                hostCoordinator?.stopLocal(clearLease = true)
            }
        } catch (_: Throwable) {
        }
        try {
            joinCoordinator?.leave()
        } catch (_: Throwable) {
        }
        hostCoordinator = null
        joinCoordinator = null
        adoptedFromLauncher = false
        _lobby.value = null
        _isHost.value = false
        _directConnect.value = null
        _members.value = emptyList()
        ActiveRoomSessionStore.clear(context, code)
    }

    companion object {
        private const val TAG = "InGameLobby"
    }
}
