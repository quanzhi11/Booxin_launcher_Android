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
        modpackLoader: String? = null,
        roomMods: List<RoomModDependency> = emptyList()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            firstRoot { root ->
                val modsJson = RoomHostDependencyService.serializeMods(roomMods)
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
                    .put("ModsJson", modsJson)
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

    /** PC LobbyService public-room heartbeat → POST /api/rooms/{code}/ping */
    suspend fun pingRoom(roomCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            firstRoot { root ->
                execute(
                    Request.Builder()
                        .url("$root/api/rooms/${roomCode.trim()}/ping")
                        .post(ByteArray(0).toRequestBody())
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

    private fun parseRoom(o: JSONObject): PublicRoom {
        val modsJson = firstNonBlank(o.optString("modsJson"), o.optString("ModsJson"))
        val modsFromArray = parseModsArray(o.optJSONArray("mods") ?: o.optJSONArray("Mods"))
        return PublicRoom(
            id = firstNonBlank(o.optString("id"), o.optString("Id")).orEmpty(),
            roomCode = firstNonBlank(o.optString("roomCode"), o.optString("RoomCode")).orEmpty(),
            hostName = firstNonBlank(o.optString("hostName"), o.optString("HostName")).orEmpty(),
            motd = firstNonBlank(o.optString("motd"), o.optString("Motd")).orEmpty(),
            remark = firstNonBlank(o.optString("remark"), o.optString("Remark")),
            port = o.optInt("port", o.optInt("Port", 25565)),
            maxPlayers = o.optInt("maxPlayers", o.optInt("MaxPlayers", 0)),
            currentPlayers = o.optInt("currentPlayers", o.optInt("CurrentPlayers", 0)),
            isPublic = o.optBoolean("isPublic", o.optBoolean("IsPublic", true)),
            version = firstNonBlank(o.optString("version"), o.optString("Version")),
            modpackUrl = firstNonBlank(o.optString("modpackUrl"), o.optString("ModpackUrl")),
            modpackGameVersion = firstNonBlank(
                o.optString("modpackGameVersion"),
                o.optString("ModpackGameVersion")
            ),
            modpackLoader = firstNonBlank(
                o.optString("modpackLoader"),
                o.optString("ModpackLoader")
            ),
            modsJson = modsJson,
            mods = modsFromArray,
            status = firstNonBlank(o.optString("status"), o.optString("Status")),
            dedicatedAddress = firstNonBlank(
                o.optString("dedicatedAddress"),
                o.optString("DedicatedAddress")
            ),
            serverAddress = firstNonBlank(
                o.optString("serverAddress"),
                o.optString("ServerAddress")
            )
        )
    }

    private fun parseModsArray(arr: JSONArray?): List<RoomModDependency> {
        if (arr == null || arr.length() == 0) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val mod = RoomHostDependencyService.parseModObject(item)
                if (mod.hasDownloadSource) add(mod)
                if (size >= RoomHostDependencyService.MAX_MODS) break
            }
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        for (v in values) {
            val t = v?.trim().orEmpty()
            if (t.isNotEmpty() && t != "null") return t
        }
        return null
    }

    private fun execute(request: Request): String {
        // Cleartext room host; dedicated client avoids any SSL-redirect to https://IP.
        HttpClients.cleartextRoom.newCall(request).execute().use { response ->
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
