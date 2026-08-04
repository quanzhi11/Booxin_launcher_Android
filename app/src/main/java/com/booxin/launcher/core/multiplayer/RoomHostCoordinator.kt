package com.booxin.launcher.core.multiplayer

import android.content.Context
import com.booxin.launcher.core.diag.DiagEventLog
import java.util.UUID

data class RoomHostResult(
    val lobby: TerracottaLobbyInfo,
    val scaffoldingPort: Int,
    val members: List<RoomMember>
)

/**
 * Host create path aligned with PC LobbyService.CreateLobbyCoreAsync.
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
        gameVersion: String? = null
    ): RoomHostResult {
        stopLocal()
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
            stopLocal()
            throw t
        }

        activeLobby = lobby
        publishedCode = roomCode
        val motd = roomName?.trim()?.ifBlank { null } ?: "${playerName}的房间"
        onStatus("正在发布房间…")
        roomApi.createRoom(
            roomCode = roomCode,
            hostId = hostId,
            hostName = playerName,
            motd = motd,
            remark = roomRemark,
            port = minecraftPort,
            isPublic = isPublic,
            version = gameVersion ?: "1.20.1"
        ).onFailure { err ->
            DiagEventLog.w(TAG, "publish room failed: ${err.message}")
        }

        val members = server.membersSnapshot()
        onMembersChanged(members)
        onStatus("房间已创建 · $roomCode")
        DiagEventLog.i(
            TAG,
            "host ok room=$roomCode sc=${server.port} mc=$minecraftPort players=${members.size}"
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
        stopLocal()
        if (code != null) {
            roomApi.deleteRoom(code).onFailure {
                DiagEventLog.w(TAG, "unpublish failed: ${it.message}")
            }
        }
        onStatus("已关闭房间")
    }

    /** Stop EasyTier / Scaffolding without directory unpublish. */
    fun stopLocal() {
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
        onMembersChanged(emptyList())
    }

    companion object {
        private const val TAG = "RoomHost"
    }
}
