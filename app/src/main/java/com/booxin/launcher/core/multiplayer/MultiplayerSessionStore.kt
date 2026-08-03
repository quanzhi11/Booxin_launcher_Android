package com.booxin.launcher.core.multiplayer

import android.content.Context
import org.json.JSONObject
import java.io.File

class MultiplayerSessionStore(context: Context) {
    private val file = File(context.filesDir, "multiplayer_session.json")

    @Synchronized
    fun load(): BooxinAuthSession? {
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(file.readText())
            val user = o.getJSONObject("user")
            BooxinAuthSession(
                accessToken = o.getString("accessToken"),
                tokenType = o.optString("tokenType", "Bearer"),
                expiresAtUtc = o.optString("expiresAtUtc").ifBlank { null },
                apiRoot = o.optString("apiRoot", BooxinMultiplayerApi.DEFAULT_ROOT),
                user = BooxinUser(
                    id = user.getString("id"),
                    username = user.getString("username"),
                    avatarUrl = user.optString("avatarUrl").ifBlank { null },
                    signature = user.optString("signature").ifBlank { null },
                    email = user.optString("email").ifBlank { null },
                    isEmailVerified = user.optBoolean("isEmailVerified", false),
                    isOnline = user.optBoolean("isOnline", false),
                    isInRoom = user.optBoolean("isInRoom", false)
                )
            )
        }.getOrNull()
    }

    @Synchronized
    fun save(session: BooxinAuthSession) {
        val user = JSONObject()
            .put("id", session.user.id)
            .put("username", session.user.username)
            .put("avatarUrl", session.user.avatarUrl)
            .put("signature", session.user.signature)
            .put("email", session.user.email)
            .put("isEmailVerified", session.user.isEmailVerified)
            .put("isOnline", session.user.isOnline)
            .put("isInRoom", session.user.isInRoom)
        val root = JSONObject()
            .put("accessToken", session.accessToken)
            .put("tokenType", session.tokenType)
            .put("expiresAtUtc", session.expiresAtUtc)
            .put("apiRoot", session.apiRoot)
            .put("user", user)
        file.parentFile?.mkdirs()
        file.writeText(root.toString())
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }
}
