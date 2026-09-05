package com.booxin.launcher.core.auth

import com.booxin.launcher.core.net.HttpClients
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID

/**
 * Microsoft device-code → Xbox → XSTS → Minecraft Services (same chain as PC AuthService).
 */
object MicrosoftAuthService {
    private const val CLIENT_ID = "2dab171b-19ac-4ff8-a9e3-e73d49593e0f"
    private const val SCOPE = "XboxLive.signin offline_access"
    private const val DEVICE_CODE_URL =
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
    private const val TOKEN_URL =
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    private const val REFRESH_URL =
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    /** Skip refresh when MC token still has ≥10 min left. */
    private const val GRACE_MS = 10 * 60 * 1000L
    /** Room join: only force-refresh when fewer than 30 min remain. */
    private const val ROOM_FORCE_REFRESH_IF_LEFT_BELOW_MS = 30 * 60 * 1000L
    /** On Mojang 429, reuse cached token if it still has ≥2 min left. */
    private const val RATE_LIMIT_FALLBACK_MIN_LEFT_MS = 2 * 60 * 1000L
    private const val MC_LOGIN_429_RETRIES = 1
    private const val MC_LOGIN_429_BASE_DELAY_MS = 5_000L

    @Volatile
    private var lastMcLogin429AtMs = 0L

    data class DeviceCodeSession(
        val userCode: String,
        val deviceCode: String,
        val verificationUri: String,
        val intervalSec: Int,
        val expiresInSec: Int
    )

    data class Progress(
        val message: String,
        val userCode: String? = null,
        val verificationUri: String? = null
    )

    suspend fun loginWithDeviceCode(
        onProgress: (Progress) -> Unit = {}
    ): Result<LauncherAccount> = withContext(Dispatchers.IO) {
        val log = MicrosoftAuthLogger.begin("device_code_login")
        runCatching {
            log.log("Step 1/6: request device code")
            val device = requestDeviceCode(log)
            log.log("device code issued user_code=${device.userCode} uri=${device.verificationUri}")
            onProgress(
                Progress(
                    message = "请在浏览器打开 ${device.verificationUri}\n输入代码：${device.userCode}",
                    userCode = device.userCode,
                    verificationUri = device.verificationUri
                )
            )
            log.log("Step 2/6: poll device code")
            val ms = pollDeviceCode(device, log, onProgress)
            log.log("Microsoft token acquired expires_in=${ms.third}")
            onProgress(Progress("正在验证 Xbox Live…"))
            buildMinecraftAccount(
                msAccess = ms.first,
                msRefresh = ms.second,
                msExpiresIn = ms.third,
                existingId = null,
                log = log,
                onProgress = onProgress
            )
        }.mapFailure(::normalizeFailure).also { result ->
            MicrosoftAuthLogger.finish(log, result.exceptionOrNull())
        }
    }

    /**
     * Reuse token if still valid (≥10 min left); otherwise refresh; otherwise failure
     * (caller should start device-code again).
     *
     * [forceRefresh] (e.g. room join) only forces a full chain when the MC token has
     * less than 30 min left — avoids hammering Mojang `login_with_xbox` (HTTP 429).
     * On 429, falls back to a still-usable cached token when possible.
     */
    suspend fun ensureSession(
        account: LauncherAccount,
        onProgress: (Progress) -> Unit = {},
        forceRefresh: Boolean = false
    ): Result<LauncherAccount> = withContext(Dispatchers.IO) {
        val log = MicrosoftAuthLogger.begin("ensure_session")
        runCatching {
            if (account.type != AccountType.MICROSOFT) return@runCatching account
            log.log("account id=${account.id} name=${account.name} forceRefresh=$forceRefresh")
            val now = System.currentTimeMillis()
            val expires = account.accessTokenExpiresAtMs ?: 0L
            val leftMs = expires - now
            val hasToken = !account.accessToken.isNullOrBlank()

            val needRefresh = when {
                !hasToken -> true
                forceRefresh && leftMs < ROOM_FORCE_REFRESH_IF_LEFT_BELOW_MS -> true
                !forceRefresh && leftMs < GRACE_MS -> true
                else -> false
            }
            if (!needRefresh) {
                log.log(
                    "session still valid (${leftMs / 1000}s left), " +
                        "skip refresh (forceRefresh=$forceRefresh)"
                )
                return@runCatching account
            }

            // Recent Mojang 429: prefer cached token instead of another login_with_xbox.
            if (hasToken && leftMs >= RATE_LIMIT_FALLBACK_MIN_LEFT_MS &&
                now - lastMcLogin429AtMs < 5 * 60 * 1000L
            ) {
                log.log(
                    "skip refresh: Mojang rate-limited recently, " +
                        "reusing cached MC token (${leftMs / 1000}s left)"
                )
                return@runCatching account
            }

            val refresh = account.refreshToken
                ?: error("微软账号需要重新登录（无 refresh token）")
            log.log(
                "Step 1/5: refresh Microsoft token " +
                    "(left=${leftMs / 1000}s forceRefresh=$forceRefresh)"
            )
            onProgress(Progress("正在刷新微软登录…"))
            try {
                val ms = refreshMicrosoftToken(refresh, log)
                buildMinecraftAccount(
                    msAccess = ms.first,
                    msRefresh = ms.second,
                    msExpiresIn = ms.third,
                    existingId = account.id,
                    log = log,
                    onProgress = onProgress
                )
            } catch (t: Throwable) {
                if (isMojangRateLimited(t) &&
                    hasToken &&
                    leftMs >= RATE_LIMIT_FALLBACK_MIN_LEFT_MS
                ) {
                    log.log(
                        "Mojang rate-limited; reusing cached MC token " +
                            "(${leftMs / 1000}s left)"
                    )
                    return@runCatching account
                }
                throw t
            }
        }.mapFailure(::normalizeFailure).also { result ->
            MicrosoftAuthLogger.finish(log, result.exceptionOrNull())
        }
    }

    private fun isMojangRateLimited(t: Throwable): Boolean {
        val msg = t.message.orEmpty()
        return msg.contains("HTTP 429") ||
            msg.contains("Too Many Requests", ignoreCase = true)
    }

    private fun normalizeFailure(t: Throwable): Throwable {
        if (t is UnknownHostException) {
            return IOException(
                "网络/DNS 异常：无法解析服务器域名（${t.message}）。请检查当前网络、系统 DNS，或切换 Wi-Fi/移动数据后重试。",
                t
            )
        }
        val msg = t.message.orEmpty()
        if (msg.contains("Unable to resolve host", ignoreCase = true)) {
            return IOException(
                "网络/DNS 异常：域名解析失败。请检查网络连通性后重试。",
                t
            )
        }
        if (isMojangRateLimited(t)) {
            return IOException(
                "微软/正版登录暂时被限流（HTTP 429）。请等待约 10–30 分钟后再试，" +
                    "或先退出联机房间再启动。若本地会话仍有效，下次启动会自动复用。",
                t
            )
        }
        return t
    }

    private inline fun <T> Result<T>.mapFailure(transform: (Throwable) -> Throwable): Result<T> {
        val error = exceptionOrNull() ?: return this
        return Result.failure(transform(error))
    }

    private fun requestDeviceCode(log: MicrosoftAuthLogger.Session): DeviceCodeSession {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .build()
        val req = Request.Builder()
            .url(DEVICE_CODE_URL)
            .header("User-Agent", HttpClients.USER_AGENT)
            .post(body)
            .build()
        HttpClients.shared.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            log.logHttp("device_code", "POST", DEVICE_CODE_URL, resp.code, text)
            if (!resp.isSuccessful) throw IOException("设备码失败 HTTP ${resp.code}: $text")
            val json = JSONObject(text)
            return DeviceCodeSession(
                userCode = json.getString("user_code"),
                deviceCode = json.getString("device_code"),
                verificationUri = json.optString(
                    "verification_uri",
                    "https://www.microsoft.com/link"
                ),
                intervalSec = json.optInt("interval", 5).coerceAtLeast(1),
                expiresInSec = json.optInt("expires_in", 900)
            )
        }
    }

    private suspend fun pollDeviceCode(
        device: DeviceCodeSession,
        log: MicrosoftAuthLogger.Session,
        onProgress: (Progress) -> Unit
    ): Triple<String, String, Int> {
        val deadline = System.currentTimeMillis() + device.expiresInSec * 1000L
        var attempt = 0
        var dnsFailStreak = 0
        while (System.currentTimeMillis() < deadline) {
            delay(device.intervalSec * 1000L)
            attempt++
            val body = FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("client_id", CLIENT_ID)
                .add("device_code", device.deviceCode)
                .add("scope", SCOPE)
                .build()
            val req = Request.Builder()
                .url(TOKEN_URL)
                .header("User-Agent", HttpClients.USER_AGENT)
                .post(body)
                .build()
            try {
                HttpClients.shared.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    if (resp.isSuccessful && json != null) {
                        log.log("poll attempt #$attempt succeeded")
                        return Triple(
                            json.getString("access_token"),
                            json.optString("refresh_token"),
                            json.optInt("expires_in", 3600)
                        )
                    }
                    dnsFailStreak = 0
                    val err = json?.optString("error").orEmpty()
                    log.log("poll attempt #$attempt HTTP ${resp.code} error=$err")
                    when (err) {
                        "authorization_pending", "slow_down" -> {
                            onProgress(
                                Progress(
                                    "等待授权中… ${device.userCode}",
                                    device.userCode,
                                    device.verificationUri
                                )
                            )
                        }
                        "expired_token" -> error("设备码已过期，请重试")
                        "access_denied" -> error("用户取消了授权")
                        else -> if (resp.code !in 400..499) {
                            // transient
                        } else if (err.isNotBlank()) {
                            log.logHttp("poll_token", "POST", TOKEN_URL, resp.code, text)
                            error("微软登录失败: $err")
                        }
                    }
                }
            } catch (e: java.net.UnknownHostException) {
                dnsFailStreak++
                log.log("poll attempt #$attempt DNS fail ($dnsFailStreak): ${e.message}")
                onProgress(
                    Progress(
                        "网络/DNS 不稳定，正在重试… ${device.userCode}",
                        device.userCode,
                        device.verificationUri
                    )
                )
                if (dnsFailStreak >= 12) throw e
            } catch (e: IOException) {
                val isDns = e is java.net.UnknownHostException ||
                    e.message.orEmpty().contains("Unable to resolve host", ignoreCase = true) ||
                    generateSequence(e.cause) { it.cause }.any { it is java.net.UnknownHostException }
                if (!isDns) throw e
                dnsFailStreak++
                log.log("poll attempt #$attempt network/DNS fail ($dnsFailStreak): ${e.message}")
                onProgress(
                    Progress(
                        "网络/DNS 不稳定，正在重试… ${device.userCode}",
                        device.userCode,
                        device.verificationUri
                    )
                )
                if (dnsFailStreak >= 12) throw e
            }
        }
        error("登录超时，设备码已过期")
    }

    private fun refreshMicrosoftToken(
        refreshToken: String,
        log: MicrosoftAuthLogger.Session
    ): Triple<String, String, Int> {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .add("scope", SCOPE)
            .build()
        val req = Request.Builder()
            .url(REFRESH_URL)
            .header("User-Agent", HttpClients.USER_AGENT)
            .post(body)
            .build()
        HttpClients.shared.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            log.logHttp("refresh_token", "POST", REFRESH_URL, resp.code, text)
            if (!resp.isSuccessful) throw IOException("刷新令牌失败 HTTP ${resp.code}: $text")
            val json = JSONObject(text)
            return Triple(
                json.getString("access_token"),
                json.optString("refresh_token", refreshToken),
                json.optInt("expires_in", 3600)
            )
        }
    }

    private fun buildMinecraftAccount(
        msAccess: String,
        msRefresh: String,
        msExpiresIn: Int,
        existingId: String?,
        log: MicrosoftAuthLogger.Session,
        onProgress: (Progress) -> Unit
    ): LauncherAccount {
        onProgress(Progress("正在验证 Xbox Live…"))
        log.log("Step: Xbox Live authenticate")
        val xboxToken = xboxAuthenticate(msAccess, log)
        onProgress(Progress("正在获取 XSTS…"))
        log.log("Step: XSTS authorize")
        val (xsts, uhs) = xstsAuthorize(xboxToken, log)
        log.log("XSTS uhs=$uhs")
        onProgress(Progress("正在登录 Minecraft…"))
        log.log("Step: Minecraft login_with_xbox")
        val (mcToken, mcExpiresIn) = loginWithXbox(uhs, xsts, log)
        onProgress(Progress("正在检查正版授权…"))
        log.log("Step: check ownership (mcstore)")
        val hasGame = checkOwnership(mcToken, log)
        if (!hasGame) {
            error("未检测到 Minecraft Java 正版授权。请确认微软账号已购买 Java 版（或 Game Pass 含 Java），并完成 Xbox 绑定。")
        }
        onProgress(Progress("正在读取角色档案…"))
        log.log("Step: fetch minecraft profile")
        val profile = minecraftProfile(mcToken, log)
        val name = profile.optString("name").ifBlank { error("档案无角色名") }
        val uuid = profile.optString("id").ifBlank { error("档案无 UUID") }
        val now = System.currentTimeMillis()
        // 用 Minecraft token 有效期，不用 MS OAuth expires_in。
        val expiresInSec = mcExpiresIn.coerceIn(60, 7 * 24 * 3600)
        log.log("mc token expires_in=${expiresInSec}s name=$name uuid=$uuid")
        return LauncherAccount(
            id = existingId ?: "ms-$uuid",
            name = name,
            type = AccountType.MICROSOFT,
            selected = true,
            uuid = uuid,
            accessToken = mcToken,
            refreshToken = msRefresh.ifBlank { null },
            accessTokenExpiresAtMs = now + expiresInSec * 1000L,
            xuid = uhs,
            userType = "msa",
            hasMinecraft = true
        )
    }

    /** Returns Xbox Live token (Token field only). */
    private fun xboxAuthenticate(msAccess: String, log: MicrosoftAuthLogger.Session): String {
        val payload = JSONObject()
            .put(
                "Properties",
                JSONObject()
                    .put("AuthMethod", "RPS")
                    .put("SiteName", "user.auth.xboxlive.com")
                    .put("RpsTicket", "d=$msAccess")
            )
            .put("RelyingParty", "http://auth.xboxlive.com")
            .put("TokenType", "JWT")
        val req = Request.Builder()
            .url("https://user.auth.xboxlive.com/user/authenticate")
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        HttpClients.shared.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            log.logHttp("xbox_auth", "POST", req.url.toString(), resp.code, text)
            if (!resp.isSuccessful) throw IOException("Xbox 认证失败 HTTP ${resp.code}: $text")
            return JSONObject(text).getString("Token")
        }
    }

    /** Returns XSTS token + uhs from XSTS DisplayClaims (same as PC AuthService). */
    private fun xstsAuthorize(xboxToken: String, log: MicrosoftAuthLogger.Session): Pair<String, String> {
        val payload = JSONObject()
            .put(
                "Properties",
                JSONObject()
                    .put("SandboxId", "RETAIL")
                    .put("UserTokens", JSONArray().put(xboxToken))
            )
            .put("RelyingParty", "rp://api.minecraftservices.com/")
            .put("TokenType", "JWT")
        val req = Request.Builder()
            .url("https://xsts.auth.xboxlive.com/xsts/authorize")
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        HttpClients.shared.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            log.logHttp("xsts", "POST", req.url.toString(), resp.code, text)
            if (!resp.isSuccessful) {
                // XErr 2148916233 = no Xbox profile; 2148916238 = child account
                throw IOException("XSTS 失败 HTTP ${resp.code}: $text")
            }
            val json = JSONObject(text)
            val token = json.getString("Token")
            val uhs = json.getJSONObject("DisplayClaims")
                .getJSONArray("xui")
                .getJSONObject(0)
                .getString("uhs")
            return token to uhs
        }
    }

    private fun loginWithXbox(uhs: String, xsts: String, log: MicrosoftAuthLogger.Session): Pair<String, Int> {
        // Match PC: only identityToken, use XSTS uhs (not user.auth uhs).
        val payload = JSONObject()
            .put("identityToken", "XBL3.0 x=$uhs;$xsts")
        var lastError: IOException? = null
        for (attempt in 0..MC_LOGIN_429_RETRIES) {
            if (attempt > 0) {
                val wait = MC_LOGIN_429_BASE_DELAY_MS * attempt
                log.log("mc_login 429 retry #$attempt after ${wait}ms")
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            val req = Request.Builder()
                .url("https://api.minecraftservices.com/authentication/login_with_xbox")
                .header("User-Agent", HttpClients.USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            HttpClients.shared.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                log.logHttp("mc_login", "POST", req.url.toString(), resp.code, text)
                if (resp.isSuccessful) {
                    val json = JSONObject(text)
                    return json.getString("access_token") to json.optInt("expires_in", 86400)
                }
                if (resp.code == 429) {
                    lastMcLogin429AtMs = System.currentTimeMillis()
                    lastError = IOException("MC 登录失败 HTTP 429: $text")
                    return@use
                }
                throw IOException("MC 登录失败 HTTP ${resp.code}: $text")
            }
        }
        throw lastError ?: IOException("MC 登录失败 HTTP 429")
    }

    private fun minecraftProfile(mcToken: String, log: MicrosoftAuthLogger.Session): JSONObject {
        val req = Request.Builder()
            .url("https://api.minecraftservices.com/minecraft/profile")
            .header("Authorization", "Bearer $mcToken")
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        HttpClients.shared.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            log.logHttp("mc_profile", "GET", req.url.toString(), resp.code, text)
            if (!resp.isSuccessful) {
                if (resp.code == 404) {
                    throw IOException(
                        "读取档案失败：此微软账号没有 Minecraft Java 角色档案（HTTP 404）。" +
                            "请确认已购买 Java 版，并在 minecraft.net 登录过一次以创建游戏名。"
                    )
                }
                throw IOException("读取档案失败 HTTP ${resp.code}: $text")
            }
            return JSONObject(text)
        }
    }

    private fun checkOwnership(mcToken: String, log: MicrosoftAuthLogger.Session): Boolean {
        val req = Request.Builder()
            .url("https://api.minecraftservices.com/entitlements/mcstore")
            .header("Authorization", "Bearer $mcToken")
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return runCatching {
            HttpClients.shared.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                log.logHttp("mcstore", "GET", req.url.toString(), resp.code, text)
                if (!resp.isSuccessful) return@use false
                val items = JSONObject(text).optJSONArray("items")
                    ?: return@use false
                if (items.length() == 0) return@use false
                for (i in 0 until items.length()) {
                    val name = items.getJSONObject(i).optString("name")
                    if (name.contains("minecraft", ignoreCase = true) ||
                        name.contains("product_minecraft", ignoreCase = true) ||
                        name.contains("game_minecraft", ignoreCase = true)
                    ) {
                        log.log("ownership matched item=$name")
                        return@use true
                    }
                }
                // Any entitlement item from mcstore usually means owned.
                log.log("ownership: ${items.length()} items, no explicit match — treating as owned")
                true
            }
        }.getOrDefault(false).also { owned ->
            log.log("ownership result=$owned")
        }
    }
}
