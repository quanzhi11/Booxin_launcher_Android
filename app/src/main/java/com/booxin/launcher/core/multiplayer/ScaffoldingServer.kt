package com.booxin.launcher.core.multiplayer

import com.booxin.launcher.core.diag.DiagEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scaffolding control-plane host — port of PC ScaffoldingServer.
 * Protocol matches [ScaffoldingClient]: type-length body frames over TCP loopback.
 */
class ScaffoldingServer(
    private val playerName: String,
    private val minecraftPort: Int,
    private val hostMachineId: String,
    private val vendor: String = "BooxinLauncher/Android"
) {
    private val started = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var cleanupJob: Job? = null
    private var scope: CoroutineScope? = null

    private data class Tracked(
        var profile: RoomMember,
        @Volatile var lastSeenMs: Long
    )

    private val tracked = ConcurrentHashMap<String, Tracked>()

    var port: Int = 0
        private set

    var onMembersChanged: ((List<RoomMember>) -> Unit)? = null

    fun membersSnapshot(): List<RoomMember> =
        tracked.values.map { it.profile }.sortedBy { if (it.isHost) 0 else 1 }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        tracked[hostMachineId] = Tracked(
            profile = RoomMember(
                name = playerName,
                machineId = hostMachineId,
                vendor = vendor,
                kind = "HOST"
            ),
            lastSeenMs = System.currentTimeMillis()
        )
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        port = ss.localPort
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        acceptJob = sc.launch { acceptLoop(ss) }
        cleanupJob = sc.launch { cleanupLoop() }
        DiagEventLog.i(TAG, "ScaffoldingServer started port=$port mc=$minecraftPort host=$playerName")
        notifyMembers()
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        acceptJob?.cancel()
        cleanupJob?.cancel()
        scope?.cancel()
        acceptJob = null
        cleanupJob = null
        scope = null
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        port = 0
        tracked.clear()
        DiagEventLog.i(TAG, "ScaffoldingServer stopped")
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (started.get() && !ss.isClosed) {
            try {
                val client = ss.accept()
                scope?.launch { handleClient(client) }
            } catch (_: Throwable) {
                if (!started.get()) break
            }
        }
    }

    private suspend fun cleanupLoop() {
        while (scope?.isActive == true && started.get()) {
            delay(5_000)
            val now = System.currentTimeMillis()
            var changed = false
            tracked.entries.removeIf { (id, tracked) ->
                if (id == hostMachineId) return@removeIf false
                if (now - tracked.lastSeenMs > PLAYER_TIMEOUT_MS) {
                    changed = true
                    true
                } else {
                    false
                }
            }
            if (changed) notifyMembers()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 30_000
        try {
            socket.use { s ->
                val input = DataInputStream(s.getInputStream())
                val output = DataOutputStream(s.getOutputStream())
                while (started.get() && !s.isClosed) {
                    val typeLen = input.readUnsignedByte()
                    val typeBytes = ByteArray(typeLen)
                    input.readFully(typeBytes)
                    val type = String(typeBytes, StandardCharsets.UTF_8)
                    val bodyLen = input.readInt()
                    require(bodyLen in 0..65536) { "bad body len $bodyLen" }
                    val bodyBytes = ByteArray(bodyLen)
                    if (bodyLen > 0) input.readFully(bodyBytes)
                    val body = String(bodyBytes, StandardCharsets.UTF_8)
                    val (status, resp) = handleRequest(type, body)
                    output.writeByte(status)
                    output.writeInt(resp.size)
                    if (resp.isNotEmpty()) output.write(resp)
                    output.flush()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleRequest(type: String, body: String): Pair<Int, ByteArray> {
        return when (type) {
            "c:ping" -> 0 to body.toByteArray(StandardCharsets.UTF_8)
            "c:protocols" -> {
                val joined = listOf(
                    "c:ping", "c:protocols", "c:server_port",
                    "c:player_ping", "c:player_profiles_list"
                ).joinToString("\u0000")
                0 to joined.toByteArray(StandardCharsets.US_ASCII)
            }
            "c:server_port" -> {
                val buf = ByteBuffer.allocate(2)
                buf.putShort(minecraftPort.toShort())
                0 to buf.array()
            }
            "c:player_profiles_list" -> {
                val arr = JSONArray()
                membersSnapshot().forEach { m ->
                    arr.put(
                        JSONObject()
                            .put("name", m.name)
                            .put("machine_id", m.machineId)
                            .put("vendor", m.vendor)
                            .put("kind", m.kind ?: if (m.isHost) "HOST" else "GUEST")
                    )
                }
                0 to arr.toString().toByteArray(StandardCharsets.UTF_8)
            }
            "c:player_ping" -> handlePlayerPing(body)
            else -> 32 to ByteArray(0)
        }
    }

    private fun handlePlayerPing(body: String): Pair<Int, ByteArray> {
        return try {
            val o = JSONObject(body)
            val machineId = o.optString("machine_id").trim()
            if (machineId.isBlank()) return 32 to ByteArray(0)
            if (machineId == hostMachineId) return 0 to ByteArray(0)
            val guest = RoomMember(
                name = o.optString("name").ifBlank { "?" },
                machineId = machineId,
                vendor = o.optString("vendor"),
                kind = "GUEST"
            )
            val existing = tracked[machineId]
            if (existing == null) {
                tracked[machineId] = Tracked(guest, System.currentTimeMillis())
                notifyMembers()
            } else {
                existing.profile = guest
                existing.lastSeenMs = System.currentTimeMillis()
            }
            0 to ByteArray(0)
        } catch (_: Throwable) {
            32 to ByteArray(0)
        }
    }

    private fun notifyMembers() {
        onMembersChanged?.invoke(membersSnapshot())
    }

    companion object {
        private const val TAG = "ScaffoldingServer"
        private const val PLAYER_TIMEOUT_MS = 10_000L
    }
}
