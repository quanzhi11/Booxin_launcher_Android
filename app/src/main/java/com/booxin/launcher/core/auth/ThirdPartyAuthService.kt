package com.booxin.launcher.core.auth

import com.booxin.launcher.core.net.HttpClients
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Yggdrasil / authlib-injector third-party login (PC AuthService.LoginThirdPartyAsync).
 * Default skin-site API root: LittleSkin.
 */
object ThirdPartyAuthService {
    const val DEFAULT_SERVER_URL = "https://littleskin.cn/api/yggdrasil"

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private const val TOKEN_TTL_MS = 24L * 60L * 60L * 1000L
    private const val GRACE_MS = 10L * 60L * 1000L

    suspend fun login(
        serverUrl: String,
        username: String,
        password: String
    ): Result<LauncherAccount> = withContext(Dispatchers.IO) {
        runCatching {
            val root = normalizeServerUrl(serverUrl)
            require(username.isNotBlank() && password.isNotBlank()) {
                "请填写用户名和密码"
            }
            val clientToken = UUID.randomUUID().toString()
            val auth = authenticate(root, username.trim(), password, clientToken)
            val accessToken = auth.optString("accessToken").trim()
            val returnedClient = auth.optString("clientToken").trim().ifBlank { clientToken }
            require(accessToken.isNotEmpty()) { "认证失败：服务器未返回 accessToken" }

            // validate is optional (LittleSkin often lacks it)
            runCatching { validate(root, accessToken, returnedClient) }

            var profile = auth.optJSONObject("selectedProfile")
            val profileId = profile?.optString("id").orEmpty().trim()
            val profileName = profile?.optString("name").orEmpty().trim()
            if (profileId.isEmpty() || profileName.isEmpty()) {
                val userObj = auth.optJSONObject("user")
                val fallbackName = userObj?.optString("username")?.trim().orEmpty()
                    .ifBlank { shortUsername(username) }
                profile = JSONObject()
                    .put("id", UUID.randomUUID().toString().replace("-", ""))
                    .put("name", fallbackName.take(16))
            } else if (profileName.length > 16) {
                profile!!.put("name", profileName.take(16))
            }

            // Prefer sessionserver profile when available
            val uuidRaw = profile!!.optString("id").trim()
            runCatching {
                fetchProfile(root, accessToken, uuidRaw)?.let { remote ->
                    val n = remote.optString("name").trim()
                    val i = remote.optString("id").trim()
                    if (n.isNotEmpty()) profile.put("name", n.take(16))
                    if (i.isNotEmpty()) profile.put("id", i)
                }
            }

            val name = profile.optString("name").trim().take(16)
            val uuid = normalizeUuidNoDash(profile.optString("id"))
            require(name.isNotEmpty() && uuid.isNotEmpty()) {
                "获取用户档案失败，请检查服务器配置"
            }

            LauncherAccount(
                id = "thirdparty-$uuid",
                name = name,
                type = AccountType.THIRD_PARTY,
                selected = true,
                uuid = uuid,
                accessToken = accessToken,
                refreshToken = returnedClient, // PC: clientToken stored as RefreshToken
                accessTokenExpiresAtMs = System.currentTimeMillis() + TOKEN_TTL_MS,
                userType = "mojang",
                hasMinecraft = true,
                thirdPartyServerUrl = root
            )
        }
    }

    suspend fun ensureSession(account: LauncherAccount): Result<LauncherAccount> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (account.type != AccountType.THIRD_PARTY) return@runCatching account
                val expires = account.accessTokenExpiresAtMs
                if (!account.accessToken.isNullOrBlank() &&
                    expires != null &&
                    expires > System.currentTimeMillis() + GRACE_MS
                ) {
                    return@runCatching account
                }
                val server = account.thirdPartyServerUrl?.trim().orEmpty()
                val access = account.accessToken?.trim().orEmpty()
                val client = account.refreshToken?.trim().orEmpty()
                require(server.isNotEmpty() && access.isNotEmpty() && client.isNotEmpty()) {
                    "第三方登录令牌已过期，请重新登录"
                }
                val refreshed = refreshTokens(server, access, client)
                    ?: error("第三方登录令牌已过期，请重新登录")
                account.copy(
                    name = refreshed.name ?: account.name,
                    uuid = refreshed.uuid ?: account.uuid,
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken,
                    accessTokenExpiresAtMs = refreshed.accessTokenExpiresAtMs,
                    userType = "mojang",
                    thirdPartyServerUrl = refreshed.thirdPartyServerUrl ?: server
                )
            }
        }

    private data class RefreshResult(
        val name: String?,
        val uuid: String?,
        val accessToken: String,
        val refreshToken: String,
        val accessTokenExpiresAtMs: Long,
        val thirdPartyServerUrl: String
    )

    private fun refreshTokens(
        serverUrl: String,
        accessToken: String,
        clientToken: String
    ): RefreshResult? {
        val root = normalizeServerUrl(serverUrl)
        val body = JSONObject()
            .put("accessToken", accessToken)
            .put("clientToken", clientToken)
            .put("requestUser", true)
            .toString()
        val req = Request.Builder()
            .url("$root/authserver/refresh")
            .header("User-Agent", "MinecraftLauncher/1.0")
            .post(body.toRequestBody(jsonMedia))
            .build()
        return HttpClients.shared.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val o = JSONObject(resp.body?.string().orEmpty())
            val newAccess = o.optString("accessToken").trim()
            val newClient = o.optString("clientToken").trim().ifBlank { clientToken }
            if (newAccess.isEmpty()) return@use null
            val profile = o.optJSONObject("selectedProfile")
            RefreshResult(
                name = profile?.optString("name")?.trim()?.take(16)?.ifBlank { null },
                uuid = profile?.optString("id")?.let { normalizeUuidNoDash(it) }?.ifBlank { null },
                accessToken = newAccess,
                refreshToken = newClient,
                accessTokenExpiresAtMs = System.currentTimeMillis() + TOKEN_TTL_MS,
                thirdPartyServerUrl = root
            )
        }
    }

    data class LaunchAgent(
        val javaAgentArg: String,
        val extraJvmArgs: List<String>,
        val apiRoot: String
    )

    /** Build authlib-injector JVM args for a third-party Yggdrasil server (PC GameService). */
    suspend fun prepareLaunchAgent(serverUrl: String): LaunchAgent? = withContext(Dispatchers.IO) {
        val root = normalizeServerUrl(serverUrl)
        val jar = com.booxin.launcher.core.skin.AuthlibInjectorInstaller.ensure().getOrElse {
            return@withContext null
        }
        val meta = prefetchMetadataBase64(root)
        val extras = buildList {
            add("-Dauthlibinjector.side=client")
            if (!meta.isNullOrBlank()) {
                add("-Dauthlibinjector.yggdrasil.prefetched=$meta")
            }
        }
        LaunchAgent(
            javaAgentArg = "-javaagent:${jar.absolutePath}=$root",
            extraJvmArgs = extras,
            apiRoot = root
        )
    }

    /** Prefetch Yggdrasil metadata for authlib-injector (base64 UTF-8). */
    suspend fun prefetchMetadataBase64(serverUrl: String): String? = withContext(Dispatchers.IO) {
        val root = normalizeServerUrl(serverUrl)
        val urls = listOf(root, "$root/metadata")
        for (url in urls) {
            val body = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "MinecraftLauncher/1.0")
                    .get()
                    .build()
                HttpClients.shared.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (!body.isNullOrBlank()) {
                return@withContext android.util.Base64.encodeToString(
                    body.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
            }
        }
        null
    }

    fun normalizeServerUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.isEmpty()) url = DEFAULT_SERVER_URL
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://$url"
        }
        return url.trimEnd('/')
    }

    private fun authenticate(
        serverUrl: String,
        username: String,
        password: String,
        clientToken: String
    ): JSONObject {
        val body = JSONObject()
            .put(
                "agent",
                JSONObject().put("name", "Minecraft").put("version", 1)
            )
            .put("username", username)
            .put("password", password)
            .put("clientToken", clientToken)
            .put("requestUser", true)
            .toString()
        val req = Request.Builder()
            .url("$serverUrl/authserver/authenticate")
            .header("User-Agent", "MinecraftLauncher/1.0")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMedia))
            .build()
        HttpClients.shared.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { JSONObject(text).optString("errorMessage") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                error(err ?: "认证失败（HTTP ${resp.code}）")
            }
            return JSONObject(text)
        }
    }

    private fun validate(serverUrl: String, accessToken: String, clientToken: String): Boolean {
        val body = JSONObject()
            .put("accessToken", accessToken)
            .put("clientToken", clientToken)
            .toString()
        val req = Request.Builder()
            .url("$serverUrl/authserver/validate")
            .header("User-Agent", "MinecraftLauncher/1.0")
            .post(body.toRequestBody(jsonMedia))
            .build()
        return HttpClients.shared.newCall(req).execute().use { it.isSuccessful }
    }

    private fun fetchProfile(
        serverUrl: String,
        accessToken: String,
        uuid: String
    ): JSONObject? {
        val id = normalizeUuidNoDash(uuid)
        if (id.isEmpty()) return null
        val req = Request.Builder()
            .url("$serverUrl/sessionserver/session/minecraft/profile/$id")
            .header("User-Agent", "MinecraftLauncher/1.0")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return HttpClients.shared.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) null else JSONObject(text)
        }
    }

    private fun shortUsername(raw: String): String {
        val base = raw.substringBefore('@').filter { it.isLetterOrDigit() || it == '_' }
        return base.take(16).ifBlank { "Player" }
    }

    private fun normalizeUuidNoDash(raw: String): String =
        raw.trim().replace("-", "").lowercase()
}
