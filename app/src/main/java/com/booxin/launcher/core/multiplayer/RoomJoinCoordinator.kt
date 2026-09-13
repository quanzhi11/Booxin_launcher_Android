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
    private var mcRelay: LocalTcpRelay? = null

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
        onStatus("等待本机 Scaffolding 转发 $localScaffoldingPort…")
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

        // EasyTier MC forward may not accept until host Open-to-LAN is up.
        // LocalTcpRelay accepts immediately (LAN MOTD / servers.dat / offline-safe).
        onStatus("转发 Minecraft 端口 ${lobby.minecraftPort}…")
        val etMcPort = session.addPortForward(host.ipv4Host, lobby.minecraftPort)
        val relay = LocalTcpRelay(
            listenHost = "0.0.0.0",
            targetHost = "127.0.0.1",
            targetPort = etMcPort
        )
        val publicPort = relay.start()
        mcRelay = relay
        onStatus("已建立本机联机入口 127.0.0.1:$publicPort（局域网也可发现）")
        DiagEventLog.i(
            TAG,
            "mc relay public=$publicPort -> et=$etMcPort -> remote=${lobby.minecraftPort}"
        )

        val address = if (publicPort == 25565) "127.0.0.1" else "127.0.0.1:$publicPort"
        val description = LanBroadcast.buildDescription(members)
        val broadcastStarted = startLanBroadcastBestEffort(description, publicPort)

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
            mcRelay?.close()
        } catch (_: Throwable) {
        }
        mcRelay = null
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

    suspend fun refreshMembers() {
        val client = scaffolding ?: return
        val members = runCatching { client.refreshMembers() }.getOrDefault(emptyList())
        onMembersChanged(members)
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
            "加入成功。请启动游戏 →「多人游戏 → 局域网」进入房间（离线/外置账号均可）。勿选手动局域网里房主真实 IP。"
    }
}
