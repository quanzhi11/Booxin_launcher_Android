package com.booxin.launcher.core.multiplayer

import com.booxin.launcher.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Room directory API (HTTP :5000). Separate from JWT social host.
 */
class BooxinRoomApi {

    companion object {
        private val ROOTS = listOf("http://175.178.174.103:5000")
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    suspend fun listPublicRooms(): Result<List<PublicRoom>> = withContext(Dispatchers.IO) {
        runCatching {
            firstRoot { root ->
                val text = execute(
                    Request.Builder()
                        .url("$root/api/rooms?isPublic=true")
                        .get()
                        .header("User-Agent", HttpClients.USER_AGENT)
                        .header("Accept", "application/json")
                        .build()
                )
                parseRooms(text)
            }
        }
    }

    suspend fun getRoom(roomCode: String): Result<PublicRoom> = withContext(Dispatchers.IO) {
        runCatching {
            firstRoot { root ->
                val text = execute(
                    Request.Builder()
                        .url("$root/api/rooms/${roomCode.trim()}")
                        .get()
                        .header("User-Agent", HttpClients.USER_AGENT)
                        .header("Accept", "application/json")
                        .build()
                )
                parseRoom(JSONObject(text))
            }
        }
    }

    suspend fun createRoom(
        roomCode: String,
        hostId: String,
        hostName: String,
        motd: String,
        remark: String? = null,
        port: Int,
        maxPlayers: Int = 8,
        isPublic: Boolean = true,
        version: String = "1.20.1",
        modpackUrl: String? = null,
        modpackGameVersion: String? = null,
        modpackLoader: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            firstRoot { root ->
                val body = JSONObject()
                    .put("RoomCode", roomCode)
                    .put("HostId", hostId)
                    .put("HostName", hostName)
                    .put("Motd", motd)
                    .put("Remark", remark.orEmpty())
                    .put("Port", port)
                    .put("MaxPlayers", maxPlayers)
                    .put("IsPublic", isPublic)
                    .put("Version", version)
                    .put("ModpackUrl", modpackUrl)
                    .put("ModpackGameVersion", modpackGameVersion)
                    .put("ModpackLoader", modpackLoader)
                    .toString()
                    .toRequestBody(JSON)
                execute(
                    Request.Builder()
                        .url("$root/api/rooms")
                        .post(body)
                        .header("User-Agent", HttpClients.USER_AGENT)
                        .header("Accept", "application/json")
                        .build()
                )
                Unit
            }
        }
    }

    suspend fun deleteRoom(roomCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            firstRoot { root ->
                execute(
                    Request.Builder()
                        .url("$root/api/rooms/${roomCode.trim()}")
                        .delete()
                        .header("User-Agent", HttpClients.USER_AGENT)
                        .header("Accept", "application/json")
                        .build()
                )
                Unit
            }
        }
    }

    suspend fun joinRoom(
        session: BooxinAuthSession,
        roomCode: String,
        displayName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            firstRoot { root ->
                val body = JSONObject()
                    .put("userId", session.user.id)
                    .put("displayName", displayName)
                    .toString()
                    .toRequestBody(JSON)
                val text = execute(
                    Request.Builder()
                        .url("$root/api/rooms/${roomCode.trim()}/join")
                        .post(body)
                        .header("Authorization", "${session.tokenType} ${session.accessToken}")
                        .header("User-Agent", HttpClients.USER_AGENT)
                        .header("Accept", "application/json")
                        .build()
                )
                runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "已加入房间"
            }
        }
    }

    suspend fun leaveRoom(session: BooxinAuthSession, roomCode: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                firstRoot { root ->
                    val body = JSONObject()
                        .put("userId", session.user.id)
                        .toString()
                        .toRequestBody(JSON)
                    val text = execute(
                        Request.Builder()
                            .url("$root/api/rooms/${roomCode.trim()}/leave")
                            .post(body)
                            .header("Authorization", "${session.tokenType} ${session.accessToken}")
                            .header("User-Agent", HttpClients.USER_AGENT)
                            .header("Accept", "application/json")
                            .build()
                    )
                    runCatching { JSONObject(text).optString("message") }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "已离开房间"
                }
            }
        }

    private fun parseRooms(text: String): List<PublicRoom> {
        val trimmed = text.trim()
        val arr = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).optJSONArray("rooms") ?: JSONArray()
        }
        return buildList {
            for (i in 0 until arr.length()) add(parseRoom(arr.getJSONObject(i)))
        }
    }

    private fun parseRoom(o: JSONObject) = PublicRoom(
        id = o.optString("id"),
        roomCode = o.optString("roomCode"),
        hostName = o.optString("hostName"),
        motd = o.optString("motd"),
        remark = o.optString("remark").ifBlank { null },
        port = o.optInt("port", 25565),
        maxPlayers = o.optInt("maxPlayers", 0),
        currentPlayers = o.optInt("currentPlayers", 0),
        isPublic = o.optBoolean("isPublic", true),
        version = o.optString("version").ifBlank { null },
        modpackUrl = o.optString("modpackUrl").ifBlank { null },
        status = o.optString("status").ifBlank { null }
    )

    private fun execute(request: Request): String {
        HttpClients.shared.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(text).optString("message")
                }.getOrNull().orEmpty().ifBlank { text.take(200) }
                throw IOException("HTTP ${response.code}: ${message.ifBlank { response.message }}")
            }
            return text
        }
    }

    private fun <T> firstRoot(block: (String) -> T): T {
        var last: Throwable? = null
        for (root in ROOTS) {
            try {
                return block(root)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IOException("无法连接房间服务器")
    }
}
