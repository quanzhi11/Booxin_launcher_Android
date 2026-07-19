package com.booxin.launcher.core.launch

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Offline account helpers aligned with HMCL/FCL offline UUID rules.
 */
object OfflineAuth {

    fun uuidFromUsername(username: String): UUID {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest("OfflinePlayer:$username".toByteArray(StandardCharsets.UTF_8))
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x30).toByte() // version 3
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // IETF variant
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (bytes[i].toLong() and 0xff)
        for (i in 8 until 16) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xff)
        return UUID(msb, lsb)
    }

    fun uuidString(username: String): String = uuidFromUsername(username).toString()

    fun uuidNoDash(username: String): String = uuidString(username).replace("-", "")
}
