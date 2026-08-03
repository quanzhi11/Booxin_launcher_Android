package com.booxin.launcher.core.multiplayer

import java.security.SecureRandom

/**
 * Terracotta (陶瓦) room-code codec — port of PC TerracottaRoomCode.
 */
object TerracottaRoomCode {
    private const val BASE_CHARS = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val PAYLOAD_LENGTH = 25
    private const val DATA_LENGTH = 24
    private const val PORT_MOD = 65536
    private const val MIN_PORT = 100
    private const val VIRTUAL_HOST_IP = "10.144.144.1"
    private val random = SecureRandom()

    fun tryParse(code: String): TerracottaLobbyInfo? {
        if (code.isBlank()) return null
        val normalized = code.trim().uppercase()
            .replace('I', '1')
            .replace('O', '0')
        if (!isValidFormatted(normalized)) return null

        val digits = IntArray(PAYLOAD_LENGTH)
        val payload = CharArray(PAYLOAD_LENGTH)
        var idx = 0
        for (c in normalized) {
            if (c == '-') continue
            val digit = BASE_CHARS.indexOf(c)
            if (digit < 0 || idx >= PAYLOAD_LENGTH) return null
            digits[idx] = digit
            payload[idx] = c
            idx++
        }
        if (idx != PAYLOAD_LENGTH) return null

        var checksum = 0
        var port = 0
        var power = 1
        for (i in 0 until DATA_LENGTH) {
            checksum = (checksum + digits[i]) % BASE_CHARS.length
            port = (port + digits[i] * power) % PORT_MOD
            power = (power * BASE_CHARS.length) % PORT_MOD
        }
        if (digits[DATA_LENGTH] != checksum || port < MIN_PORT) return null

        val payloadText = String(payload)
        return TerracottaLobbyInfo(
            roomCode = normalized,
            networkName = payloadText.substring(0, 15).lowercase(),
            networkSecret = payloadText.substring(15, 25).lowercase(),
            minecraftPort = port,
            virtualHostIp = VIRTUAL_HOST_IP
        )
    }

    fun generate(port: Int): String {
        require(port in MIN_PORT..65535) { "端口必须在 $MIN_PORT-65535" }
        val digits = IntArray(PAYLOAD_LENGTH)
        for (i in 4 until DATA_LENGTH) {
            digits[i] = random.nextInt(BASE_CHARS.length)
        }
        val high = computePortModulo(digits.copyOfRange(4, DATA_LENGTH))
        var low = resolveLowValue(port, high)
        for (i in 0 until 4) {
            digits[i] = low % BASE_CHARS.length
            low /= BASE_CHARS.length
        }
        var checksum = 0
        for (i in 0 until DATA_LENGTH) {
            checksum = (checksum + digits[i]) % BASE_CHARS.length
        }
        digits[DATA_LENGTH] = checksum
        val raw = CharArray(PAYLOAD_LENGTH) { BASE_CHARS[digits[it]] }
        val out = CharArray(29)
        var src = 0
        for (i in out.indices) {
            if (i > 0 && i % 6 == 5) {
                out[i] = '-'
            } else {
                out[i] = raw[src++]
            }
        }
        return String(out)
    }

    private fun isValidFormatted(code: String): Boolean {
        if (code.length != 29) return false
        for (i in code.indices) {
            val hyphen = i == 5 || i == 11 || i == 17 || i == 23
            if (hyphen) {
                if (code[i] != '-') return false
            } else if (BASE_CHARS.indexOf(code[i]) < 0) {
                return false
            }
        }
        return true
    }

    private fun computePortModulo(digits: IntArray): Int {
        var result = 0
        var power = 1
        repeat(4) { power = (power * BASE_CHARS.length) % PORT_MOD }
        for (digit in digits) {
            result = (result + digit * power) % PORT_MOD
            power = (power * BASE_CHARS.length) % PORT_MOD
        }
        return result
    }

    private fun resolveLowValue(port: Int, highValueMod: Int): Int {
        val residue = ((port - highValueMod) % PORT_MOD + PORT_MOD) % PORT_MOD
        val maxCandidate = Math.pow(BASE_CHARS.length.toDouble(), 4.0).toInt() - 1
        val maxK = (maxCandidate - residue) / PORT_MOD
        val k = random.nextInt(maxK + 1)
        return residue + PORT_MOD * k
    }
}
