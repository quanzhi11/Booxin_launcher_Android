package com.booxin.launcher.core.multiplayer

import android.content.Context
import com.booxin.launcher.core.diag.DiagEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class RoomHostResult(
    val lobby: TerracottaLobbyInfo,
    val scaffoldingPort: Int,
    val members: List<RoomMember>
)

/**
 * Host create path aligned with PC LobbyService.CreateLobbyCoreAsync.
 *
 * Also mirrors PC public-room heartbeat + Dispose delete so crash / exit
 * does not leave zombie rooms in the directory.
 */
class RoomHostCoordinator(
    private val context: Context,
    private val roomApi: BooxinRoomApi = BooxinRoomApi(),
    private val onStatus: (String) -> Unit = {},
    private val onMembersChanged: (List<RoomMember>) -> Unit = {}
) {
    private var easyTier: EasyTierSession? = null
    private var scaffolding: ScaffoldingServer? = null
    private var publishedCode: String? = null
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var activeLobby: TerracottaLobbyInfo? = null
        private set

    suspend fun create(
        minecraftPort: Int,
        playerName: String,
        hostId: String,
        isPublic: Boolean = true,
        roomName: String? = null,
        roomRemark: String? = null,
        gameVersion: String? = null,
        modpackUrl: String? = null,
        modpackGameVersion: String? = null,
        modpackLoader: String? = null,
        roomMods: List<RoomModDependency> = emptyList()
    ): RoomHostResult {
        // Drop previous public listing before starting a new host session.
        leaveQuietly()
        require(minecraftPort in 100..65535) { "端口必须在 100-65535" }
        onStatus("正在生成房间码…")
        val roomCode = TerracottaRoomCode.generate(minecraftPort)
        val lobby = TerracottaRoomCode.tryParse(roomCode)
            ?: error("房间码生成失败")
        val mid = "android-host-${UUID.randomUUID()}"

        onStatus("正在启动 Scaffolding…")
        val server = ScaffoldingServer(
            playerName = playerName,
            minecraftPort = minecraftPort,
            hostMachineId = mid
        )
        server.onMembersChanged = { members -> onMembersChanged(members) }
        server.start()
        scaffolding = server

        onStatus("正在启动 EasyTier 房主…")
        val session = EasyTierSession.create(context)
        easyTier = session
        EasyTierSessionHolder.current = session
        try {
            session.launchHost(
                lobby = lobby,
                minecraftPort = minecraftPort,
                scaffoldingPort = server.port,
                machineId = mid
            )
        } catch (t: Throwable) {
            stopLocal(clearLease = true)
            throw t
        }

        activeLobby = lobby
        publishedCode = roomCode
        val motd = roomName?.trim()?.ifBlank { null } ?: "${playerName}的房间"
        val publishVersion = modpackGameVersion?.takeIf { it.isNotBlank() }
            ?: gameVersion
            ?: "1.20.1"
        onStatus("正在发布房间…")
        // Persist before HTTP so a crash mid-publish / right after still gets swept.
        HostedRoomLease.mark(context, roomCode)
        roomApi.createRoom(
            roomCode = roomCode,
            hostId = hostId,
            hostName = playerName,
            motd = motd,
            remark = roomRemark,
            port = minecraftPort,
            isPublic = isPublic,
            version = publishVersion,
            modpackUrl = modpackUrl?.trim()?.takeIf { it.isNotEmpty() },
            modpackGameVersion = modpackGameVersion ?: gameVersion,
            modpackLoader = modpackLoader,
            roomMods = roomMods
        ).onFailure { err ->
            DiagEventLog.w(TAG, "publish room failed: ${err.message}")
            HostedRoomLease.clear(context, roomCode)
        }.onSuccess {
            if (isPublic) startPublicRoomHeartbeat(roomCode)
        }

        val members = server.membersSnapshot()
        onMembersChanged(members)
        onStatus("房间已创建 · $roomCode")
        DiagEventLog.i(
            TAG,
            "host ok room=$roomCode sc=${server.port} mc=$minecraftPort players=${members.size} mods=${roomMods.size}"
        )
        return RoomHostResult(
            lobby = lobby,
            scaffoldingPort = server.port,
            members = members
        )
    }

    fun currentMembers(): List<RoomMember> = scaffolding?.membersSnapshot().orEmpty()

    suspend fun leave() {
        val code = publishedCode ?: activeLobby?.roomCode
        stopLocal(clearLease = false)
        if (code != null) {
            roomApi.deleteRoom(code).onFailure {
                DiagEventLog.w(TAG, "unpublish failed: ${it.message}")
            }
            HostedRoomLease.clear(context, code)
        } else {
            HostedRoomLease.clear(context)
        }
        onStatus("已关闭房间")
    }

    /** Best-effort unpublish used when starting a new room or disposing. */
    suspend fun leaveQuietly() {
        runCatching { leave() }
    }

    /** Stop EasyTier / Scaffolding. [clearLease] only when never published. */
    fun stopLocal(clearLease: Boolean = true) {
        stopPublicRoomHeartbeat()
        val code = publishedCode
        publishedCode = null
        activeLobby = null
        try {
            scaffolding?.stop()
        } catch (_: Throwable) {
        }
        scaffolding = null
        try {
            easyTier?.stop()
        } catch (_: Throwable) {
        }
        easyTier = null
        EasyTierSessionHolder.stop()
        if (clearLease) {
            HostedRoomLease.clear(context, code)
        }
        onMembersChanged(emptyList())
    }

    private fun startPublicRoomHeartbeat(roomCode: String) {
        stopPublicRoomHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    delay(HEARTBEAT_INTERVAL_MS)
                    roomApi.pingRoom(roomCode).onFailure { err ->
                        DiagEventLog.w(
                            TAG,
                            "public room heartbeat failed room=$roomCode: ${err.message}"
                        )
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    break
                } catch (t: Throwable) {
                    DiagEventLog.w(TAG, "public room heartbeat error room=$roomCode: ${t.message}")
                }
            }
        }
    }

    private fun stopPublicRoomHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    companion object {
        private const val TAG = "RoomHost"
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
    }
}
