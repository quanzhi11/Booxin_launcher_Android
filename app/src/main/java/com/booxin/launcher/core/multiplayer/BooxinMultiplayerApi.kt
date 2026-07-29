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
import java.net.URLEncoder

/**
 * Booxin social API (auth / friends / messages). Room directory is [BooxinRoomApi].
 */
class BooxinMultiplayerApi {

    companion object {
        const val DEFAULT_ROOT = "https://boonix.art/bbx"
        private val ROOTS = listOf(
            "https://boonix.art/bbx",
            "https://175.178.174.103/bbx"
        )
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    suspend fun login(username: String, password: String): Result<BooxinAuthSession> =
        authPost("login", JSONObject().put("username", username.trim()).put("password", password))

    suspend fun loginWithEmail(email: String, code: String): Result<BooxinAuthSession> =
        authPost(
            "email/login",
            JSONObject().put("email", email.trim()).put("code", code.trim())
        )

    suspend fun register(
        username: String,
        password: String,
        email: String,
        emailCode: String
    ): Result<BooxinAuthSession> =
        authPost(
            "register",
            JSONObject()
                .put("username", username.trim())
                .put("password", password)
                .put("email", email.trim())
                .put("emailCode", emailCode.trim())
        )

    suspend fun sendRegisterCode(email: String): Result<String> =
        messagePost("register/send-code", JSONObject().put("email", email.trim()))

    suspend fun sendEmailLoginCode(email: String): Result<String> =
        messagePost("email/login/send-code", JSONObject().put("email", email.trim()))

    suspend fun sendPasswordResetCode(email: String): Result<String> =
        messagePost("password/forgot/send-code", JSONObject().put("email", email.trim()))

    suspend fun resetPassword(email: String, code: String, newPassword: String): Result<String> =
        messagePost(
            "password/reset",
            JSONObject()
                .put("email", email.trim())
                .put("code", code.trim())
                .put("newPassword", newPassword)
        )

    suspend fun fetchMe(session: BooxinAuthSession): Result<BooxinUser> =
        withContext(Dispatchers.IO) {
            runCatching {
                parseUser(JSONObject(execute(authorizedGet(session, "${session.apiRoot}/api/auth/me"))))
            }
        }

    suspend fun fetchFriends(session: BooxinAuthSession): Result<FriendsDashboard> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = JSONObject(execute(authorizedGet(session, "${session.apiRoot}/api/friends")))
                FriendsDashboard(
                    friends = parseFriendArray(root.optJSONArray("friends")),
                    incomingRequests = parseRequestArray(root.optJSONArray("incomingRequests")),
                    outgoingRequests = parseRequestArray(root.optJSONArray("outgoingRequests")),
                    pendingRoomInvites = parseInviteArray(root.optJSONArray("pendingRoomInvites")),
                    blockedUsers = parseBlockedArray(root.optJSONArray("blockedUsers"))
                )
            }
        }

    suspend fun fetchLobby(session: BooxinAuthSession, page: Int = 1, pageSize: Int = 40) =
        withContext(Dispatchers.IO) {
            runCatching {
                val url =
                    "${session.apiRoot}/api/auth/users/lobby?page=$page&pageSize=$pageSize&onlineOnly=false"
                parseLobby(JSONObject(execute(authorizedGet(session, url))).optJSONArray("results"))
            }
        }

    suspend fun searchUsers(session: BooxinAuthSession, query: String, limit: Int = 20) =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
                val url = "${session.apiRoot}/api/auth/users/search?query=$encoded&limit=$limit"
                parseSearch(JSONObject(execute(authorizedGet(session, url))).optJSONArray("results"))
            }
        }

    suspend fun sendFriendRequest(
        session: BooxinAuthSession,
        targetUserId: String? = null,
        username: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
            if (!targetUserId.isNullOrBlank()) payload.put("targetUserId", targetUserId)
            if (!username.isNullOrBlank()) payload.put("username", username.trim())
            messageFrom(
                execute(
                    authorizedPost(
                        session,
                        "${session.apiRoot}/api/friends/request",
                        payload.toString().toRequestBody(JSON)
                    )
                ),
                "好友请求已发送"
            )
        }
    }

    suspend fun acceptFriendRequest(session: BooxinAuthSession, requestId: String) =
        friendAction(session, "requests/$requestId/accept", "已添加好友")

    suspend fun rejectFriendRequest(session: BooxinAuthSession, requestId: String) =
        friendAction(session, "requests/$requestId/reject", "已拒绝请求")

    suspend fun removeFriend(session: BooxinAuthSession, friendUserId: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = execute(
                    authorizedDelete(session, "${session.apiRoot}/api/friends/$friendUserId")
                )
                if (text.isBlank()) "已删除好友" else messageFrom(text, "已删除好友")
            }
        }

    suspend fun blockUser(session: BooxinAuthSession, userId: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                messageFrom(
                    execute(
                        authorizedPost(
                            session,
                            "${session.apiRoot}/api/friends/block/$userId",
                            "{}".toRequestBody(JSON)
                        )
                    ),
                    "已拉黑"
                )
            }
        }

    suspend fun unblockUser(session: BooxinAuthSession, userId: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = execute(
                    authorizedDelete(session, "${session.apiRoot}/api/friends/block/$userId")
                )
                if (text.isBlank()) "已取消拉黑" else messageFrom(text, "已取消拉黑")
            }
        }

    suspend fun dismissInvite(session: BooxinAuthSession, inviteId: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                messageFrom(
                    execute(
                        authorizedPost(
                            session,
                            "${session.apiRoot}/api/friends/invites/$inviteId/dismiss",
                            "{}".toRequestBody(JSON)
                        )
                    ),
                    "已忽略邀请"
                )
            }
        }

    suspend fun updatePresence(
        session: BooxinAuthSession,
        isInRoom: Boolean = false,
        roomCode: String? = null,
        isAvailableToChat: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("isInRoom", isInRoom)
                .put("isAvailableToChat", isAvailableToChat)
            if (roomCode.isNullOrBlank()) body.put("roomCode", JSONObject.NULL)
            else body.put("roomCode", roomCode)
            execute(
                authorizedPost(
                    session,
                    "${session.apiRoot}/api/friends/presence",
                    body.toString().toRequestBody(JSON)
                )
            )
            Unit
        }
    }

    suspend fun listConversations(session: BooxinAuthSession): Result<List<ChatConversation>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = JSONObject(
                    execute(authorizedGet(session, "${session.apiRoot}/api/messages/conversations"))
                )
                val arr = root.optJSONArray("conversations") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            ChatConversation(
                                peerUserId = o.optString("peerUserId"),
                                peerUsername = o.optString("peerUsername"),
                                peerAvatarUrl = o.optString("peerAvatarUrl").ifBlank { null },
                                lastMessageBody = o.optString("lastMessageBody").ifBlank { null },
                                lastMessageAtUtc = o.optString("lastMessageAtUtc").ifBlank { null },
                                unreadCount = o.optInt("unreadCount", 0)
                            )
                        )
                    }
                }
            }
        }

    suspend fun getMessages(
        session: BooxinAuthSession,
        peerUserId: String,
        afterId: Long = 0L
    ): Result<List<ChatMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${session.apiRoot}/api/messages/with/$peerUserId?afterId=$afterId"
            val root = JSONObject(execute(authorizedGet(session, url)))
            parseMessages(root.optJSONArray("messages"))
        }
    }

    suspend fun sendMessage(
        session: BooxinAuthSession,
        receiverId: String,
        body: String
    ): Result<ChatMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("receiverId", receiverId)
                .put("body", body)
                .toString()
                .toRequestBody(JSON)
            val o = JSONObject(
                execute(authorizedPost(session, "${session.apiRoot}/api/messages", payload))
            )
            parseMessage(o)
        }
    }

    suspend fun markRead(
        session: BooxinAuthSession,
        peerUserId: String,
        upToMessageId: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("peerUserId", peerUserId)
                .put("upToMessageId", upToMessageId)
                .toString()
                .toRequestBody(JSON)
            execute(authorizedPost(session, "${session.apiRoot}/api/messages/read", payload))
            Unit
        }
    }

    suspend fun recallMessage(session: BooxinAuthSession, messageId: Long): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                messageFrom(
                    execute(
                        authorizedPost(
                            session,
                            "${session.apiRoot}/api/messages/$messageId/recall",
                            "{}".toRequestBody(JSON)
                        )
                    ),
                    "已撤回"
                )
            }
        }

    suspend fun updateSignature(session: BooxinAuthSession, signature: String): Result<BooxinUser> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject()
                    .put("signature", signature.trim().take(80))
                    .toString()
                    .toRequestBody(JSON)
                execute(
                    authorizedPatch(session, "${session.apiRoot}/api/auth/profile", payload)
                )
                parseUser(JSONObject(execute(authorizedGet(session, "${session.apiRoot}/api/auth/me"))))
            }
        }

    suspend fun changePassword(
        session: BooxinAuthSession,
        currentPassword: String,
        newPassword: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            messageFrom(
                execute(
                    authorizedPost(
                        session,
                        "${session.apiRoot}/api/auth/change-password",
                        JSONObject()
                            .put("currentPassword", currentPassword)
                            .put("newPassword", newPassword)
                            .toString()
                            .toRequestBody(JSON)
                    )
                ),
                "密码已修改"
            )
        }
    }

    suspend fun sendBindEmailCode(session: BooxinAuthSession, email: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                messageFrom(
                    execute(
                        authorizedPost(
                            session,
                            "${session.apiRoot}/api/auth/email/send-code",
                            JSONObject().put("email", email.trim()).toString().toRequestBody(JSON)
                        )
                    ),
                    "验证码已发送"
                )
            }
        }

    suspend fun verifyBindEmail(
        session: BooxinAuthSession,
        email: String,
        code: String
    ): Result<BooxinUser> = withContext(Dispatchers.IO) {
        runCatching {
            val text = execute(
                authorizedPost(
                    session,
                    "${session.apiRoot}/api/auth/email/verify",
                    JSONObject()
                        .put("email", email.trim())
                        .put("code", code.trim())
                        .toString()
                        .toRequestBody(JSON)
                )
            )
            runCatching { parseUser(JSONObject(text)) }.getOrElse {
                parseUser(JSONObject(execute(authorizedGet(session, "${session.apiRoot}/api/auth/me"))))
            }
        }
    }

    suspend fun unbindEmail(session: BooxinAuthSession): Result<BooxinUser> =
        withContext(Dispatchers.IO) {
            runCatching {
                execute(authorizedDelete(session, "${session.apiRoot}/api/auth/email"))
                parseUser(JSONObject(execute(authorizedGet(session, "${session.apiRoot}/api/auth/me"))))
            }
        }

    suspend fun uploadAvatar(session: BooxinAuthSession, bytes: ByteArray, mime: String): Result<BooxinUser> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ext = when {
                    mime.contains("png") -> "png"
                    mime.contains("webp") -> "webp"
                    else -> "jpg"
                }
                val body = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        "avatar.$ext",
                        bytes.toRequestBody(mime.toMediaType())
                    )
                    .build()
                val text = execute(
                    authorizedPost(session, "${session.apiRoot}/api/auth/avatar", body)
                )
                runCatching { parseUser(JSONObject(text)) }.getOrElse {
                    parseUser(JSONObject(execute(authorizedGet(session, "${session.apiRoot}/api/auth/me"))))
                }
            }
        }

    suspend fun deleteAvatar(session: BooxinAuthSession): Result<BooxinUser> =
        withContext(Dispatchers.IO) {
            runCatching {
                execute(authorizedDelete(session, "${session.apiRoot}/api/auth/avatar"))
                parseUser(JSONObject(execute(authorizedGet(session, "${session.apiRoot}/api/auth/me"))))
            }
        }

    suspend fun sendRoomInvite(session: BooxinAuthSession, friendUserId: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                messageFrom(
                    execute(
                        authorizedPost(
                            session,
                            "${session.apiRoot}/api/friends/invites",
                            JSONObject().put("friendUserId", friendUserId).toString().toRequestBody(JSON)
                        )
                    ),
                    "邀请已发送"
                )
            }
        }

    private suspend fun friendAction(
        session: BooxinAuthSession,
        path: String,
        fallback: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            messageFrom(
                execute(
                    authorizedPost(
                        session,
                        "${session.apiRoot}/api/friends/$path",
                        "{}".toRequestBody(JSON)
                    )
                ),
                fallback
            )
        }
    }

    private suspend fun authPost(path: String, body: JSONObject): Result<BooxinAuthSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                firstSuccessRoot { root ->
                    parseAuthResponse(
                        execute(post("$root/api/auth/$path", body.toString().toRequestBody(JSON))),
                        root
                    )
                }
            }
        }

    private suspend fun messagePost(path: String, body: JSONObject): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                firstSuccessRoot { root ->
                    messageFrom(
                        execute(post("$root/api/auth/$path", body.toString().toRequestBody(JSON))),
                        "OK"
                    )
                }
            }
        }

    private fun messageFrom(text: String, fallback: String): String =
        runCatching { JSONObject(text).optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: fallback

    private fun post(url: String, body: okhttp3.RequestBody): Request =
        Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Accept", "application/json")
            .build()

    private fun authorizedGet(session: BooxinAuthSession, url: String): Request =
        Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "${session.tokenType} ${session.accessToken}")
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Accept", "application/json")
            .build()

    private fun authorizedPost(
        session: BooxinAuthSession,
        url: String,
        body: okhttp3.RequestBody
    ): Request =
        Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", "${session.tokenType} ${session.accessToken}")
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Accept", "application/json")
            .build()

    private fun authorizedPatch(
        session: BooxinAuthSession,
        url: String,
        body: okhttp3.RequestBody
    ): Request =
        Request.Builder()
            .url(url)
            .patch(body)
            .header("Authorization", "${session.tokenType} ${session.accessToken}")
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Accept", "application/json")
            .build()

    private fun authorizedDelete(session: BooxinAuthSession, url: String): Request =
        Request.Builder()
            .url(url)
            .delete()
            .header("Authorization", "${session.tokenType} ${session.accessToken}")
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Accept", "application/json")
            .build()

    private fun execute(request: Request): String {
        HttpClients.shared.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(text).optString("message")
                        .ifBlank { JSONObject(text).optString("error") }
                }.getOrNull().orEmpty().ifBlank { text.take(200) }
                throw IOException("HTTP ${response.code}: ${message.ifBlank { response.message }}")
            }
            return text
        }
    }

    private fun <T> firstSuccessRoot(block: (String) -> T): T {
        var last: Throwable? = null
        for (root in ROOTS) {
            try {
                return block(root)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IOException("无法连接联机服务器")
    }

    private fun parseAuthResponse(body: String, apiRoot: String): BooxinAuthSession {
        val o = JSONObject(body)
        return BooxinAuthSession(
            accessToken = o.getString("accessToken"),
            tokenType = o.optString("tokenType", "Bearer").ifBlank { "Bearer" },
            expiresAtUtc = o.optString("expiresAtUtc").ifBlank { null },
            user = parseUser(o.getJSONObject("user")),
            apiRoot = apiRoot
        )
    }

    private fun parseUser(o: JSONObject) = BooxinUser(
        id = o.getString("id"),
        username = o.getString("username"),
        avatarUrl = o.optString("avatarUrl").ifBlank { null },
        signature = o.optString("signature").ifBlank { null },
        email = o.optString("email").ifBlank { null },
        isEmailVerified = o.optBoolean("isEmailVerified", false),
        isOnline = o.optBoolean("isOnline", false),
        isInRoom = o.optBoolean("isInRoom", false)
    )

    private fun parseFriendArray(arr: JSONArray?): List<BooxinFriend> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    BooxinFriend(
                        userId = o.optString("userId").ifBlank { o.optString("id") },
                        username = o.optString("username"),
                        avatarUrl = o.optString("avatarUrl").ifBlank { null },
                        isOnline = o.optBoolean("isOnline", false),
                        isInRoom = o.optBoolean("isInRoom", false),
                        isAvailableToChat = o.optBoolean("isAvailableToChat", false),
                        relationship = o.optString("relationship").ifBlank { null },
                        roomCode = o.optString("roomCode").ifBlank { null }
                    )
                )
            }
        }
    }

    private fun parseRequestArray(arr: JSONArray?): List<FriendRequest> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    FriendRequest(
                        requestId = o.optString("requestId").ifBlank { o.optString("id") },
                        userId = o.optString("userId").ifBlank { o.optString("id") },
                        username = o.optString("username"),
                        avatarUrl = o.optString("avatarUrl").ifBlank { null }
                    )
                )
            }
        }
    }

    private fun parseInviteArray(arr: JSONArray?): List<RoomInvite> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    RoomInvite(
                        inviteId = o.optString("inviteId").ifBlank { o.optString("id") },
                        fromUserId = o.optString("senderUserId")
                            .ifBlank { o.optString("fromUserId") }
                            .ifBlank { o.optString("userId") },
                        fromUsername = o.optString("senderUsername")
                            .ifBlank { o.optString("fromUsername") }
                            .ifBlank { o.optString("username") },
                        fromAvatarUrl = o.optString("senderAvatarUrl")
                            .ifBlank { o.optString("fromAvatarUrl") }
                            .ifBlank { o.optString("avatarUrl") }
                            .ifBlank { null },
                        roomCode = o.optString("roomCode"),
                        createdAtUtc = o.optString("createdAtUtc").ifBlank { null }
                    )
                )
            }
        }
    }

    private fun parseBlockedArray(arr: JSONArray?): List<BlockedUser> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    BlockedUser(
                        userId = o.optString("userId").ifBlank { o.optString("id") },
                        username = o.optString("username"),
                        avatarUrl = o.optString("avatarUrl").ifBlank { null }
                    )
                )
            }
        }
    }

    private fun parseLobby(arr: JSONArray?): List<LobbyUser> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    LobbyUser(
                        id = o.optString("id"),
                        username = o.optString("username"),
                        avatarUrl = o.optString("avatarUrl").ifBlank { null },
                        isOnline = o.optBoolean("isOnline", false),
                        isFriend = o.optBoolean("isFriend", false)
                    )
                )
            }
        }
    }

    private fun parseSearch(arr: JSONArray?): List<SearchUser> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    SearchUser(
                        id = o.optString("id"),
                        username = o.optString("username"),
                        avatarUrl = o.optString("avatarUrl").ifBlank { null },
                        isOnline = o.optBoolean("isOnline", false),
                        isFriend = o.optBoolean("isFriend", false)
                    )
                )
            }
        }
    }

    private fun parseMessages(arr: JSONArray?): List<ChatMessage> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                add(parseMessage(arr.getJSONObject(i)))
            }
        }
    }

    private fun parseMessage(o: JSONObject) = ChatMessage(
        id = o.optLong("id"),
        senderId = o.optString("senderId"),
        receiverId = o.optString("receiverId"),
        body = o.optString("body"),
        sentAtUtc = o.optString("sentAtUtc").ifBlank { null },
        isMine = o.optBoolean("isMine", false),
        isRevoked = o.optBoolean("isRevoked", false),
        canRecall = o.optBoolean("canRecall", false)
    )
}
