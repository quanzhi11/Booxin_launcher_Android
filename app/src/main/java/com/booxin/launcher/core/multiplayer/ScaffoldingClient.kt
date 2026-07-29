package com.booxin.launcher.core.multiplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Scaffolding control-plane client — matches PC ScaffoldingClient protocol
 * (player_ping heartbeat + player_profiles_list).
 */
class ScaffoldingClient(
    private val host: String,
    private val port: Int,
    private val playerName: String,
    private val machineId: String,
    private val vendor: String = "BooxinLauncher/Android"
) {
    private val requestLock = Mutex()
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var heartbeatScope: CoroutineScope? = null
    private var heartbeatJob: Job? = null

    @Volatile
    var lastMembers: List<RoomMember> = emptyList()
        private set

    suspend fun connect(timeoutMs: Int = 8000) = withContext(Dispatchers.IO) {
        closeQuietly()
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = 10000
        socket = s
        input = DataInputStream(s.getInputStream())
        output = DataOutputStream(s.getOutputStream())
        requestLock.withLock {
            sendPlayerPingUnlocked()
        }
    }

    /**
     * Keep alive like PC HeartbeatLoopAsync: ping every 5s then refresh member list.
     */
    fun startHeartbeat(onMembers: (List<RoomMember>) -> Unit) {
        stopHeartbeat()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        heartbeatScope = scope
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(5_000)
                try {
                    val members = refreshMembers()
                    onMembers(members)
                } catch (_: Throwable) {
                    break
                }
            }
        }
    }

    suspend fun refreshMembers(): List<RoomMember> = withContext(Dispatchers.IO) {
        requestLock.withLock {
            sendPlayerPingUnlocked()
            val body = sendRequestUnlocked("c:player_profiles_list", "")
            parseMembers(String(body, StandardCharsets.UTF_8)).also { lastMembers = it }
        }
    }

    suspend fun getPlayerProfiles(): List<JSONObject> = withContext(Dispatchers.IO) {
        requestLock.withLock {
            val body = sendRequestUnlocked("c:player_profiles_list", "")
            parseProfiles(String(body, StandardCharsets.UTF_8))
        }
    }

    private fun sendPlayerPingUnlocked() {
        val body = JSONObject()
            .put("name", playerName)
            .put("machine_id", machineId)
            .put("vendor", vendor)
            .toString()
        sendRequestUnlocked("c:player_ping", body)
    }

    private fun sendRequestUnlocked(type: String, body: String): ByteArray {
        val out = output ?: error("Scaffolding 未连接")
        val inp = input ?: error("Scaffolding 未连接")
        val typeBytes = type.toByteArray(StandardCharsets.UTF_8)
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        require(typeBytes.size <= 255) { "type too long" }
        out.writeByte(typeBytes.size)
        out.write(typeBytes)
        out.writeInt(bodyBytes.size)
        out.write(bodyBytes)
        out.flush()

        val status = inp.readUnsignedByte()
        val bodyLen = inp.readInt()
        require(bodyLen in 0..65536) { "bad body len $bodyLen" }
        val resp = ByteArray(bodyLen)
        inp.readFully(resp)
        if (status != 0) {
            error("Scaffolding 请求失败 status=$status type=$type")
        }
        return resp
    }

    private fun parseMembers(json: String): List<RoomMember> =
        parseProfiles(json).map { o ->
            RoomMember(
                name = o.optString("name").ifBlank { "?" },
                machineId = o.optString("machine_id"),
                vendor = o.optString("vendor"),
                kind = o.optString("kind").ifBlank { null }
            )
        }

    private fun parseProfiles(json: String): List<JSONObject> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) add(arr.getJSONObject(i))
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun close() {
        stopHeartbeat()
        closeQuietly()
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatScope?.cancel()
        heartbeatScope = null
    }

    private fun closeQuietly() {
        try {
            input?.close()
        } catch (_: Throwable) {
        }
        try {
            output?.close()
        } catch (_: Throwable) {
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        input = null
        output = null
        socket = null
    }
}

suspend fun waitForLocalTcp(port: Int, attempts: Int = 40, delayMs: Long = 100L) {
    withContext(Dispatchers.IO) {
        repeat(attempts) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 300)
                    return@withContext
                }
            } catch (_: Throwable) {
            }
            kotlinx.coroutines.delay(delayMs)
        }
        error("本地端口 $port 未就绪")
    }
}

fun parseHostScaffoldingPort(hostname: String): Int {
    val prefix = EasyTierPeer.HOSTNAME_PREFIX
    require(hostname.startsWith(prefix)) { "无效房主节点名: $hostname" }
    return hostname.removePrefix(prefix).toIntOrNull()
        ?: error("无效房主节点名: $hostname")
}
