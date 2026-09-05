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
    val members: List<RoomMember> = emptyList(),
    /** True when guest-side LAN MOTD rebroadcast was started (best-effort). */
    val lanBroadcastStarted: Boolean = false
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
    private var lanBroadcast: LanBroadcast? = null

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
        val description = LanBroadcast.buildDescription(members)
        val broadcastStarted = startLanBroadcastBestEffort(description, localMcPort)

        onStatus(JOIN_SUCCESS_STATUS)
        DiagEventLog.i(
            TAG,
            "join ok room=${lobby.roomCode} addr=$address lanBroadcast=$broadcastStarted players=${members.size}"
        )
        return RoomJoinResult(
            lobby = lobby,
            directConnectAddress = address,
            hostHostname = host.hostname,
            playerCount = members.size,
            members = members,
            lanBroadcastStarted = broadcastStarted
        )
    }

    fun leave() {
        stopLanBroadcast()
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

    private fun startLanBroadcastBestEffort(description: String, localMcPort: Int): Boolean {
        stopLanBroadcast()
        return try {
            val broadcast = LanBroadcast(
                context = context,
                description = description,
                localPort = localMcPort
            )
            broadcast.start()
            lanBroadcast = broadcast
            true
        } catch (t: Throwable) {
            DiagEventLog.w(TAG, "LAN broadcast start failed (join continues): ${t.message}")
            lanBroadcast = null
            false
        }
    }

    private fun stopLanBroadcast() {
        try {
            lanBroadcast?.close()
        } catch (t: Throwable) {
            DiagEventLog.w(TAG, "LAN broadcast stop failed: ${t.message}")
        }
        lanBroadcast = null
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
        const val JOIN_SUCCESS_STATUS =
            "加入成功。请打开游戏 → 多人游戏 → 在局域网列表中进入房间（不要手动填 127.0.0.1，除非局域网列表没有出现）"
    }
}
