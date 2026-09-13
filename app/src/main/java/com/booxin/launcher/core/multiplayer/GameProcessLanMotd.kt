package com.booxin.launcher.core.multiplayer

import android.content.Context
import com.booxin.launcher.core.diag.DiagEventLog

/**
 * Starts guest LAN MOTD inside the `:game` process.
 *
 * Multicast sent only from the main process is often invisible to Minecraft in `:game`
 * on Android OEMs — same-device LAN list stays empty. Broadcasting beside the JVM fixes that.
 */
object GameProcessLanMotd {
    @Volatile
    private var broadcast: LanBroadcast? = null

    fun startIfRoomActive(context: Context) {
        stop()
        val snap = ActiveRoomSessionStore.load(context)
        if (snap == null) {
            DiagEventLog.w(TAG, "skip LAN MOTD: no active_room_session.json")
            return
        }
        if (snap.isHost) {
            DiagEventLog.i(TAG, "skip LAN MOTD: this device is host")
            return
        }
        val addr = snap.directConnect?.trim().orEmpty()
        if (addr.isBlank()) {
            DiagEventLog.w(TAG, "skip LAN MOTD: directConnect empty room=${snap.roomCode}")
            return
        }
        val port = parsePort(addr)
        if (port == null) {
            DiagEventLog.w(TAG, "skip LAN MOTD: bad address $addr")
            return
        }
        val description = LanBroadcast.buildDescription(snap.members)
            .ifBlank { "Booxin 联机大厅" }
        val b = LanBroadcast(
            context = context.applicationContext,
            description = description,
            localPort = port
        )
        b.start()
        broadcast = b
        DiagEventLog.i(TAG, "game-process LAN MOTD start port=$port desc=$description")
    }

    fun stop() {
        runCatching { broadcast?.close() }
        if (broadcast != null) {
            DiagEventLog.i(TAG, "game-process LAN MOTD stopped")
        }
        broadcast = null
    }

    private fun parsePort(address: String): Int? {
        val trimmed = address.trim()
        val portPart = when {
            trimmed.startsWith("[") -> trimmed.substringAfter("]:").trim()
            ":" in trimmed -> trimmed.substringAfterLast(':').trim()
            else -> return 25565
        }
        if (portPart.isBlank()) return 25565
        return portPart.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private const val TAG = "GameLanMotd"
}
