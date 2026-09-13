package com.booxin.launcher.core.multiplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

data class MinecraftStatusProbeResult(
    val versionName: String,
    val protocol: Int,
    val onlinePlayers: Int = -1,
    val maxPlayers: Int = -1
)

/**
 * Minimal Minecraft Server List Ping (status) probe for local LAN worlds.
 * Mirrors PC [MinecraftStatusProbe] with a shorter protocol-version list for mobile latency.
 */
object MinecraftStatusProbe {
    private val handshakeProtocols = intArrayOf(
        -1, 767, 766, 765, 764, 763, 762, 761, 760, 759, 758, 757, 754,
        578, 340, 47
    )
    private const val connectTimeoutMs = 800
    private const val readTimeoutMs = 1200
    private const val maxResponseLength = 256 * 1024

    suspend fun probe(host: String, port: Int): MinecraftStatusProbeResult? =
        withContext(Dispatchers.IO) {
            for (protocol in handshakeProtocols) {
                probeOnce(host, port, protocol)?.let { return@withContext it }
            }
            null
        }

    private fun probeOnce(host: String, port: Int, protocol: Int): MinecraftStatusProbeResult? {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            out.write(buildHandshakePacket(host, port, protocol))
            out.write(buildStatusRequestPacket())
            out.flush()

            val totalLength = readVarInt(input)
            if (totalLength <= 0 || totalLength > maxResponseLength) return null
            val payload = ByteArray(totalLength)
            input.readFully(payload)
            var offset = 0
            val packetId = readVarInt(payload, offset).also { offset = it.second }.first
            if (packetId != 0) return null
            val jsonLength = readVarInt(payload, offset).also { offset = it.second }.first
            if (jsonLength <= 0 || offset + jsonLength > payload.size) return null
            val json = String(payload, offset, jsonLength, StandardCharsets.UTF_8)
            val root = JSONObject(json)
            val version = root.optJSONObject("version") ?: return null
            val protocolNum = version.optInt("protocol", 0)
            if (protocolNum <= 0) return null
            val players = root.optJSONObject("players")
            return MinecraftStatusProbeResult(
                versionName = version.optString("name").orEmpty(),
                protocol = protocolNum,
                onlinePlayers = players?.optInt("online", -1) ?: -1,
                maxPlayers = players?.optInt("max", -1) ?: -1
            )
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun buildHandshakePacket(host: String, port: Int, protocol: Int): ByteArray {
        val hostBytes = host.toByteArray(StandardCharsets.UTF_8)
        val packet = concat(
            encodeVarInt(0),
            encodeVarInt(protocol),
            encodeVarInt(hostBytes.size),
            hostBytes,
            byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte()),
            encodeVarInt(1)
        )
        return concat(encodeVarInt(packet.size), packet)
    }

    private fun buildStatusRequestPacket(): ByteArray {
        val packetId = encodeVarInt(0)
        return concat(encodeVarInt(packetId.size), packetId)
    }

    private fun encodeVarInt(value: Int): ByteArray {
        var remaining = value.toLong() and 0xFFFFFFFFL
        val bytes = ArrayList<Byte>(5)
        do {
            var current = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) current = current or 0x80
            bytes.add(current.toByte())
        } while (remaining != 0L)
        return bytes.toByteArray()
    }

    private fun readVarInt(input: DataInputStream): Int {
        var value = 0
        var position = 0
        while (position < 32) {
            val current = input.readUnsignedByte()
            value = value or ((current and 0x7F) shl position)
            if ((current and 0x80) == 0) return value
            position += 7
        }
        error("invalid varint")
    }

    private fun readVarInt(buffer: ByteArray, start: Int): Pair<Int, Int> {
        var value = 0
        var position = 0
        var offset = start
        while (offset < buffer.size && position < 32) {
            val current = buffer[offset++].toInt() and 0xFF
            value = value or ((current and 0x7F) shl position)
            if ((current and 0x80) == 0) return value to offset
            position += 7
        }
        error("invalid varint")
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val size = parts.sumOf { it.size }
        val out = ByteArray(size)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }
}
