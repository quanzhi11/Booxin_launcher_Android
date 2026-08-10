package com.booxin.launcher.core.ai

import com.booxin.launcher.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

class AiBackendClient(
    private val modelSettings: AiModelSettings
) {
    private val resolvedBase = AtomicReference(AiBackendSettings.baseUrlCandidates.first())
    private var resolvedAtMs = 0L
    private var resolveOk = false

    val resolvedBaseUrl: String get() = resolvedBase.get()

    suspend fun tryResolveBaseUrl(): Boolean = withContext(Dispatchers.IO) {
        if (resolveOk && System.currentTimeMillis() - resolvedAtMs < CACHE_TTL_MS) return@withContext true
        for (candidate in AiBackendSettings.baseUrlCandidates) {
            val ok = runCatching {
                val req = Request.Builder()
                    .url(candidate.trimEnd('/') + "/api/health")
                    .header("User-Agent", HttpClients.USER_AGENT)
                    .get()
                    .build()
                HttpClients.cascadeAttempt.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) {
                resolvedBase.set(candidate.trimEnd('/'))
                resolveOk = true
                resolvedAtMs = System.currentTimeMillis()
                return@withContext true
            }
        }
        resolvedBase.set(AiBackendSettings.baseUrlCandidates.first().trimEnd('/'))
        resolveOk = false
        false
    }

    suspend fun chatCompletion(
        selectedProvider: AiModelProvider,
        jsonBody: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            tryResolveBaseUrl()
            var provider = selectedProvider
            val profile = modelSettings.resolveRequestProfile()
            if (provider == AiModelProvider.AUTO || provider == AiModelProvider.CUSTOM) {
                if (profile.provider == AiModelProvider.CUSTOM) {
                    return@runCatching postCustom(jsonBody)
                }
                provider = profile.provider
            }
            val req = Request.Builder()
                .url(resolvedBaseUrl + AiBackendSettings.CHAT_PATH)
                .header("Authorization", "Bearer ${AiBackendSettings.CLIENT_ACCESS_KEY}")
                .header(AiBackendSettings.PROVIDER_HEADER, AiModelProfiles.toUpstreamProviderHeader(provider))
                .header("User-Agent", HttpClients.USER_AGENT)
                .header("Content-Type", "application/json")
                .post(jsonBody.toRequestBody(JSON))
                .build()
            HttpClients.shared.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("AI HTTP ${resp.code}: ${body.take(300)}")
                body
            }
        }
    }

    private fun postCustom(jsonBody: String): String {
        if (!modelSettings.isCustomReady) error("请先在设置中填写自定义 API 地址与 API Key")
        val url = normalizeCustomUrl(modelSettings.customBaseUrl)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${modelSettings.customApiKey}")
            .header("User-Agent", "BooxinLauncher-CustomAI")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(JSON))
            .build()
        return HttpClients.shared.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("自定义 API HTTP ${resp.code}: ${body.take(300)}")
            body
        }
    }

    suspend fun getQuotaSnapshot(userKey: String): AiQuotaSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            tryResolveBaseUrl()
            val url = "$resolvedBaseUrl/api/ai/quota/snapshot?userKey=${java.net.URLEncoder.encode(userKey, "UTF-8")}"
            val req = authGet(url)
            HttpClients.cascadeAttempt.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                parseSnapshot(JSONObject(resp.body?.string().orEmpty()))
            }
        }.getOrNull()
    }

    suspend fun consumeQuota(
        userKey: String,
        quotaType: AiQuotaType,
        tokens: Long,
        provider: String?,
        modelId: String?,
        promptTokens: Long,
        completionTokens: Long,
        costFen: Long = 0
    ): AiQuotaConsumeResult? = withContext(Dispatchers.IO) {
        runCatching {
            tryResolveBaseUrl()
            val payload = JSONObject()
                .put("userKey", userKey)
                .put("quotaType", when (quotaType) {
                    AiQuotaType.CHAT -> "Chat"
                    AiQuotaType.AGENT -> "Agent"
                    AiQuotaType.PLAYER -> "Player"
                })
                .put("tokens", tokens)
                .put("promptTokens", promptTokens)
                .put("completionTokens", completionTokens)
                .put("provider", provider)
                .put("modelId", modelId)
                .put("costFen", costFen)
            val req = authPost("$resolvedBaseUrl/api/ai/quota/consume", payload.toString())
            HttpClients.shared.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (!resp.isSuccessful) {
                    return@runCatching AiQuotaConsumeResult(
                        success = false,
                        message = json.optString("message").ifBlank { "扣减失败 HTTP ${resp.code}" }
                    )
                }
                AiQuotaConsumeResult(
                    success = json.optBoolean("success", true),
                    message = json.optString("message"),
                    snapshot = json.optJSONObject("snapshot")?.let { parseSnapshot(it) },
                    consumedTokens = json.optLong("consumedTokens"),
                    consumedCostFen = json.optLong("consumedCostFen")
                )
            }
        }.getOrNull()
    }

    suspend fun claimDailyPlayPass(userKey: String): AiPlayPassResult =
        postPlayPass("$resolvedBaseUrl/api/ai/quota/claim-play-pass", userKey)

    suspend fun activatePlayPass(userKey: String): AiPlayPassResult =
        postPlayPass("$resolvedBaseUrl/api/ai/quota/activate-play-pass", userKey)

    suspend fun redeemBonus(userKey: String): AiPlayPassResult =
        postPlayPass("$resolvedBaseUrl/api/ai/quota/redeem-bonus", userKey)

    suspend fun createSubscription(
        userKey: String,
        plan: String,
        payType: String
    ): AiSubscriptionCreateResult = withContext(Dispatchers.IO) {
        runCatching {
            tryResolveBaseUrl()
            val payload = JSONObject()
                .put("userKey", userKey)
                .put("plan", plan)
                .put("payType", payType)
            val req = authPost("$resolvedBaseUrl/api/ai/subscription/create", payload.toString())
            HttpClients.shared.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (!resp.isSuccessful) {
                    return@runCatching AiSubscriptionCreateResult(
                        success = false,
                        message = json.optString("message").ifBlank { "创建订单失败 HTTP ${resp.code}" }
                    )
                }
                AiSubscriptionCreateResult(
                    success = json.optBoolean("success", true),
                    message = json.optString("message"),
                    outTradeNo = json.optString("outTradeNo").ifBlank { null },
                    payUrl = json.optString("payUrl").ifBlank { null },
                    money = json.optString("money").ifBlank { null },
                    plan = json.optString("plan").ifBlank { null }
                )
            }
        }.getOrElse {
            AiSubscriptionCreateResult(success = false, message = it.message ?: "无法连接支付服务")
        }
    }

    private suspend fun postPlayPass(url: String, userKey: String): AiPlayPassResult =
        withContext(Dispatchers.IO) {
            runCatching {
                tryResolveBaseUrl()
                val req = authPost(url, JSONObject().put("userKey", userKey).toString())
                HttpClients.shared.newCall(req).execute().use { resp ->
                    val json = JSONObject(resp.body?.string().orEmpty())
                    AiPlayPassResult(
                        success = resp.isSuccessful && json.optBoolean("success", resp.isSuccessful),
                        message = json.optString("message"),
                        snapshot = json.optJSONObject("snapshot")?.let { parseSnapshot(it) }
                    )
                }
            }.getOrElse {
                AiPlayPassResult(success = false, message = it.message ?: "请求失败")
            }
        }

    private fun authGet(url: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${AiBackendSettings.CLIENT_ACCESS_KEY}")
            .header("User-Agent", HttpClients.USER_AGENT)
            .get()
            .build()

    private fun authPost(url: String, json: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${AiBackendSettings.CLIENT_ACCESS_KEY}")
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Content-Type", "application/json")
            .post(json.toRequestBody(JSON))
            .build()

    private fun parseSnapshot(o: JSONObject): AiQuotaSnapshot = AiQuotaSnapshot(
        weeklyLimitFen = o.optLong("weeklyLimitFen"),
        chatCostFen = o.optLong("chatCostFen"),
        agentCostFen = o.optLong("agentCostFen"),
        playerCostFen = o.optLong("playerCostFen"),
        chatTokensUsed = o.optLong("chatTokensUsed"),
        agentTokensUsed = o.optLong("agentTokensUsed"),
        playerTokensUsed = o.optLong("playerTokensUsed"),
        playerLimitFen = o.optLong("playerLimitFen"),
        isMember = o.optBoolean("isMember"),
        memberTier = o.optString("memberTier", "None"),
        memberExpiresAtUtc = o.optString("memberExpiresAtUtc").ifBlank { null },
        redemptionOnlyAccount = o.optBoolean("redemptionOnlyAccount"),
        redeemedCodeCount = o.optInt("redeemedCodeCount"),
        periodLabel = o.optString("periodLabel", "本周"),
        isPlayPassActive = o.optBoolean("isPlayPassActive"),
        playPassExpiresAtUtc = o.optString("playPassExpiresAtUtc").ifBlank { null },
        playPassStock = o.optInt("playPassStock"),
        tempQuotaFen = o.optLong("tempQuotaFen"),
        tempQuotaUsedFen = o.optLong("tempQuotaUsedFen"),
        tempQuotaRemainingFen = o.optLong("tempQuotaRemainingFen"),
        canClaimDailyPlayPass = o.optBoolean("canClaimDailyPlayPass"),
        canActivatePlayPass = o.optBoolean("canActivatePlayPass")
    )

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        fun normalizeCustomUrl(baseUrl: String): String {
            val url = baseUrl.trim().trimEnd('/')
            if (url.contains("chat/completions", ignoreCase = true)) return url
            if (url.endsWith("/v1", ignoreCase = true)) return "$url/chat/completions"
            return "$url/v1/chat/completions"
        }
    }
}
