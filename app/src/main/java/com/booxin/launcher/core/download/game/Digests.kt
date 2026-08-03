package com.booxin.launcher.core.download.game

import java.io.File
import java.security.MessageDigest

object Digests {
    fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matchesSha1(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return file.exists() && file.length() > 0L
        if (!file.exists()) return false
        return sha1(file).equals(expected, ignoreCase = true)
    }
}
