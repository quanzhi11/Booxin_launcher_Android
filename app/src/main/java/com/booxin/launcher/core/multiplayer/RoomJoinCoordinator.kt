package com.booxin.launcher.core.multiplayer

import android.content.Context
import com.booxin.launcher.core.diag.DiagEventLog
import kotlinx.coroutines.delay
import java.util.UUID

data class RoomJoinResult(
    val lobby: TerracottaLobbyInfo,
    val directConnectAddress: String,
    val hostHostname: String,
    val playerCount: Int,
    val members: List<RoomMember> = emptyList()
)

/**
 * Full guest join path aligned with PC LobbyService.JoinLobbyCoreAsync.
 */
class RoomJoinCoordinator(
    private val context: Context,
    private val onStatus: (String) -> Unit = {},
    private val onMembersChanged: (List<RoomMember>) -> Unit = {}
) {
    private var easyTier: EasyTierSession? = null
    private var scaffolding: ScaffoldingClient? = null

    suspend fun join(roomCode: String, playerName: String): RoomJoinResult {
        leave()
        onStatus("正在解析房间码…")
        val lobby = TerracottaRoomCode.tryParse(roomCode)
            ?: error("无效的 Booxin 房间码（需要 29 位长码，无 U/ 前缀）")

        onStatus("正在启动 EasyTier…")
        val session = EasyTierSession.create(context)
        easyTier = session
        EasyTierSessionHolder.current = session
        val machineId = "android-${UUID.randomUUID()}"
        session.launchJoin(lobby, machineId)

        onStatus("正在寻找房主节点…")
        val host = session.waitForHostPeer()
        val scaffoldingPort = parseHostScaffoldingPort(host.hostname)
        onStatus("已发现房主 ${host.hostname}，转发 Scaffolding…")

        val localScaffoldingPort = session.addPortForward(host.ipv4Host, scaffoldingPort)
        waitForLocalTcp(localScaffoldingPort)

        onStatus("Scaffolding 握手…")
        val client = ScaffoldingClient(
            host = "127.0.0.1",
            port = localScaffoldingPort,
            playerName = playerName,
            machineId = machineId
        )
        connectScaffoldingWithRetry(client)
        scaffolding = client

        val members = runCatching { client.refreshMembers() }.getOrDefault(emptyList())
        onMembersChanged(members)
        client.startHeartbeat { updated ->
            onMembersChanged(updated)
        }

        onStatus("转发 Minecraft 端口 ${lobby.minecraftPort}…")
        val localMcPort = session.addPortForward(host.ipv4Host, lobby.minecraftPort)
        waitForLocalTcp(localMcPort)

        val address = if (localMcPort == 25565) "127.0.0.1" else "127.0.0.1:$localMcPort"
        onStatus("加入成功 · 直连 $address · ${members.size} 人")
        DiagEventLog.i(TAG, "join ok room=${lobby.roomCode} addr=$address players=${members.size}")
        return RoomJoinResult(
            lobby = lobby,
            directConnectAddress = address,
            hostHostname = host.hostname,
            playerCount = members.size,
            members = members
        )
    }

    fun leave() {
        try {
            scaffolding?.close()
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

    private suspend fun connectScaffoldingWithRetry(client: ScaffoldingClient, attempts: Int = 8) {
        var last: Throwable? = null
        repeat(attempts) { i ->
            try {
                client.connect()
                return
            } catch (t: Throwable) {
                last = t
                DiagEventLog.w(TAG, "scaffolding attempt ${i + 1}: ${t.message}")
                delay(350)
            }
        }
        throw last ?: IllegalStateException("Scaffolding 连接失败")
    }

    companion object {
        private const val TAG = "RoomJoin"
    }
}
