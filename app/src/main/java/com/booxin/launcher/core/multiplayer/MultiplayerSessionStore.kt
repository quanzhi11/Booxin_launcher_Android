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
                apiRoot = normalizeApiRoot(
                    o.optString("apiRoot", BooxinMultiplayerApi.DEFAULT_ROOT)
                ),
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
            .put("apiRoot", normalizeApiRoot(session.apiRoot))
            .put("user", user)
        file.parentFile?.mkdirs()
        file.writeText(root.toString())
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    companion object {
        /** Rewrite legacy https://IP/... roots so TLS hostname matches the cert. */
        fun normalizeApiRoot(root: String): String {
            val trimmed = root.trim().trimEnd('/')
            if (trimmed.isBlank()) return BooxinMultiplayerApi.DEFAULT_ROOT
            return trimmed
                .replace("https://175.178.174.103/bbx", BooxinMultiplayerApi.DEFAULT_ROOT)
                .replace("https://175.178.174.103/eco", BooxinMultiplayerApi.DEFAULT_ECO_ROOT)
                .replace("http://175.178.174.103/bbx", BooxinMultiplayerApi.DEFAULT_ROOT)
        }
    }
}
