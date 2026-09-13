package com.booxin.launcher.core.multiplayer

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cross-process active room snapshot (main ↔ `:game`).
 * Joining in the launcher writes this file; the in-game panel reads it so UI stays in sync.
 */
object ActiveRoomSessionStore {
    const val ACTION_LEAVE_ACTIVE_ROOM = "com.booxin.launcher.action.LEAVE_ACTIVE_ROOM"
    private const val FILE_NAME = "active_room_session.json"

    data class Snapshot(
        val roomCode: String,
        val isHost: Boolean,
        val directConnect: String? = null,
        val networkName: String = "",
        val networkSecret: String = "",
        val minecraftPort: Int = 25565,
        val members: List<RoomMember> = emptyList(),
        val updatedAtMs: Long = System.currentTimeMillis()
    ) {
        fun toLobby(): TerracottaLobbyInfo =
            TerracottaRoomCode.tryParse(roomCode)
                ?: TerracottaLobbyInfo(
                    roomCode = roomCode,
                    networkName = networkName,
                    networkSecret = networkSecret,
                    minecraftPort = minecraftPort.coerceIn(100, 65535)
                )
    }

    @Synchronized
    fun save(context: Context, snapshot: Snapshot) {
        val code = snapshot.roomCode.trim()
        if (code.isEmpty()) return
        val membersJson = JSONArray()
        snapshot.members.forEach { m ->
            membersJson.put(
                JSONObject()
                    .put("name", m.name)
                    .put("machineId", m.machineId)
                    .put("vendor", m.vendor)
                    .put("kind", m.kind)
            )
        }
        val root = JSONObject()
            .put("roomCode", code)
            .put("isHost", snapshot.isHost)
            .put("directConnect", snapshot.directConnect)
            .put("networkName", snapshot.networkName)
            .put("networkSecret", snapshot.networkSecret)
            .put("minecraftPort", snapshot.minecraftPort)
            .put("members", membersJson)
            .put("updatedAtMs", System.currentTimeMillis())
        file(context).apply {
            parentFile?.mkdirs()
            writeText(root.toString())
        }
    }

    @Synchronized
    fun saveFromLobby(
        context: Context,
        lobby: TerracottaLobbyInfo,
        isHost: Boolean,
        directConnect: String? = null,
        members: List<RoomMember> = emptyList()
    ) {
        save(
            context,
            Snapshot(
                roomCode = lobby.roomCode,
                isHost = isHost,
                directConnect = directConnect,
                networkName = lobby.networkName,
                networkSecret = lobby.networkSecret,
                minecraftPort = lobby.minecraftPort,
                members = members
            )
        )
    }

    @Synchronized
    fun updateMembers(context: Context, members: List<RoomMember>) {
        val cur = load(context) ?: return
        save(context, cur.copy(members = members, updatedAtMs = System.currentTimeMillis()))
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
    fun load(context: Context): Snapshot? {
        val f = file(context)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val code = o.optString("roomCode").trim()
            if (code.isEmpty()) return null
            val members = mutableListOf<RoomMember>()
            val arr = o.optJSONArray("members")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    members += RoomMember(
                        name = m.optString("name"),
                        machineId = m.optString("machineId"),
                        vendor = m.optString("vendor"),
                        kind = m.optString("kind").ifBlank { null }
                    )
                }
            }
            Snapshot(
                roomCode = code,
                isHost = o.optBoolean("isHost", false),
                directConnect = o.optString("directConnect").takeIf { it.isNotBlank() },
                networkName = o.optString("networkName"),
                networkSecret = o.optString("networkSecret"),
                minecraftPort = o.optInt("minecraftPort", 25565),
                members = members,
                updatedAtMs = o.optLong("updatedAtMs", 0L)
            )
        }.getOrNull()
    }

    fun requestLeaveAcrossProcesses(context: Context) {
        val app = context.applicationContext
        app.sendBroadcast(
            Intent(ACTION_LEAVE_ACTIVE_ROOM).setPackage(app.packageName)
        )
    }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)
}
