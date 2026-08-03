package com.booxin.launcher.core.multiplayer

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Minimal writer for Minecraft client `servers.dat` (uncompressed NBT).
 * Mirrors PC [MinecraftServerListService.EnsureServerListed].
 */
object MinecraftServerList {

    fun ensureListed(
        gameDir: File,
        serverName: String,
        serverAddress: String,
        pinToTop: Boolean = true
    ): Boolean {
        val name = serverName.trim().ifBlank { return false }
        val address = sanitizeAddress(serverAddress) ?: return false
        gameDir.mkdirs()
        val file = File(gameDir, "servers.dat")

        val existing = if (file.isFile) {
            runCatching { parseServers(file.readBytes()) }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        val others = existing.filterNot {
            sanitizeAddress(it.ip)?.equals(address, ignoreCase = true) == true
        }
        val entry = ServerEntry(name = name, ip = address, acceptTextures = 1)
        val next = if (pinToTop) listOf(entry) + others else others + entry
        file.writeBytes(encodeServers(next))
        return true
    }

    private fun sanitizeAddress(raw: String): String? {
        val trimmed = raw.trim().trim('"', '\'')
        if (trimmed.isBlank()) return null
        val hostPart = trimmed.substringBefore(':').filterNot { it.isWhitespace() }
        if (hostPart.isBlank()) return null
        val portPart = trimmed.substringAfter(':', missingDelimiterValue = "").trim()
        val port = portPart.toIntOrNull()
        return if (port != null && port > 0) "$hostPart:$port" else hostPart
    }

    private data class ServerEntry(
        val name: String,
        val ip: String,
        val acceptTextures: Byte = 1
    )

    /** Best-effort read; on failure caller treats as empty list. */
    private fun parseServers(bytes: ByteArray): List<ServerEntry> {
        // Full NBT parser is unnecessary for our write-path; if the file exists
        // with unknown layout we overwrite with a fresh list containing the
        // official entry only when parse fails (caller handles empty).
        if (bytes.isEmpty()) return emptyList()
        // Keep previous entries when possible by scanning UTF strings for ip/name pairs.
        // Safer approach: rewrite from scratch with only the official server when
        // we cannot reliably parse — but try a tiny compound-list reader first.
        return readServerList(bytes)
    }

    private fun readServerList(bytes: ByteArray): List<ServerEntry> {
        // Uncompressed root compound with name "" (Java edition servers.dat).
        var i = 0
        fun u8(): Int = bytes[i++].toInt() and 0xff
        fun u16(): Int {
            val v = ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
            i += 2
            return v
        }
        fun i32(): Int {
            val v = ((bytes[i].toInt() and 0xff) shl 24) or
                ((bytes[i + 1].toInt() and 0xff) shl 16) or
                ((bytes[i + 2].toInt() and 0xff) shl 8) or
                (bytes[i + 3].toInt() and 0xff)
            i += 4
            return v
        }
        fun str(): String {
            val len = u16()
            val s = String(bytes, i, len, StandardCharsets.UTF_8)
            i += len
            return s
        }
        fun skipTag(type: Int) {
            when (type) {
                0 -> Unit
                1 -> i += 1
                2 -> i += 2
                3, 5 -> i += 4
                4, 6 -> i += 8
                7 -> i += 4 + i32()
                8 -> {
                    val len = u16(); i += len
                }
                9 -> {
                    val elem = u8()
                    val count = i32()
                    repeat(count) { skipTag(elem) }
                }
                10 -> {
                    while (true) {
                        val t = u8()
                        if (t == 0) break
                        str()
                        skipTag(t)
                    }
                }
                11 -> i += 4 + i32() * 4
                12 -> i += 4 + i32() * 8
                else -> error("unknown nbt type $type")
            }
        }

        // Root: TAG_Compound named ""
        require(u8() == 10)
        str() // root name
        val out = mutableListOf<ServerEntry>()
        while (i < bytes.size) {
            val t = u8()
            if (t == 0) break
            val key = str()
            if (t == 9 && key == "servers") {
                val elem = u8()
                val count = i32()
                require(elem == 10)
                repeat(count) {
                    var name = ""
                    var ip = ""
                    var accept: Byte = 1
                    while (true) {
                        val ft = u8()
                        if (ft == 0) break
                        val fk = str()
                        when {
                            ft == 8 && fk == "name" -> name = str()
                            ft == 8 && fk == "ip" -> ip = str()
                            ft == 1 && fk == "acceptTextures" -> accept = u8().toByte()
                            else -> skipTag(ft)
                        }
                    }
                    if (ip.isNotBlank()) out += ServerEntry(name, ip, accept)
                }
            } else {
                skipTag(t)
            }
        }
        return out
    }

    private fun encodeServers(entries: List<ServerEntry>): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        // Root compound ""
        out.writeByte(10)
        writeString(out, "")
        // List "servers" of compounds
        out.writeByte(9)
        writeString(out, "servers")
        out.writeByte(10) // element type compound
        out.writeInt(entries.size)
        for (e in entries) {
            out.writeByte(8)
            writeString(out, "name")
            writeString(out, e.name)
            out.writeByte(8)
            writeString(out, "ip")
            writeString(out, e.ip)
            out.writeByte(1)
            writeString(out, "acceptTextures")
            out.writeByte(e.acceptTextures.toInt())
            out.writeByte(0) // end compound
        }
        out.writeByte(0) // end root
        out.flush()
        return buf.toByteArray()
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        out.writeShort(bytes.size)
        out.write(bytes)
    }
}
