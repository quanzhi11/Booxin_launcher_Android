package com.booxin.launcher.core.multiplayer

import android.content.Context
import android.os.Process
import org.json.JSONObject
import java.io.File

/**
 * Persists the currently published host room so a crash / SIGKILL of `:game`
 * can still unpublish from the main process (PC Dispose → DeleteRoom).
 *
 * Shared across processes via app filesDir.
 */
object HostedRoomLease {
    private const val FILE_NAME = "hosted_room_lease.json"

    data class Lease(
        val roomCode: String,
        val hostPid: Int,
        val publishedAtMs: Long
    )

    @Synchronized
    fun mark(context: Context, roomCode: String) {
        val code = roomCode.trim()
        if (code.isEmpty()) return
        val root = JSONObject()
            .put("roomCode", code)
            .put("hostPid", Process.myPid())
            .put("publishedAtMs", System.currentTimeMillis())
        file(context).apply {
            parentFile?.mkdirs()
            writeText(root.toString())
        }
    }

    @Synchronized
    fun clear(context: Context, roomCode: String? = null) {
        val f = file(context)
        if (!f.exists()) return
        if (roomCode != null) {
            val current = load(context)?.roomCode
            if (current != null && !current.equals(roomCode.trim(), ignoreCase = true)) {
                return
            }
        }
        f.delete()
    }

    @Synchronized
    fun load(context: Context): Lease? {
        val f = file(context)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val code = o.optString("roomCode").trim()
            if (code.isEmpty()) return null
            Lease(
                roomCode = code,
                hostPid = o.optInt("hostPid", -1),
                publishedAtMs = o.optLong("publishedAtMs", 0L)
            )
        }.getOrNull()
    }

    fun isHostProcessAlive(lease: Lease): Boolean {
        if (lease.hostPid <= 0) return false
        if (lease.hostPid == Process.myPid()) return true
        return File("/proc/${lease.hostPid}").exists()
    }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)
}
