package com.booxin.launcher.core.multiplayer

import com.booxin.launcher.AppContainer
import com.booxin.launcher.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloud server control-plane client — behavior aligned with PC CloudServerService.
 * Android never probes 127.0.0.1.
 */
class CloudServerService(
    private val accessTokenProvider: () -> String?
) {

    companion object {
        val DEFAULT_VERSIONS: List<String> = listOf("1.12.2", "1.20.1", "1.21.11")

        private val API_ROOTS = listOf(
            "https://boonix.art/cloud",
            "http://175.178.174.103:5015"
        )

        private val LOCAL_WHITELIST = setOf("zrr", "booxin")
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        fun isLocallyWhitelisted(username: String?): Boolean {
            val name = username?.trim().orEmpty()
            return name.isNotEmpty() && LOCAL_WHITELIST.contains(name.lowercase())
        }
    }

    @Volatile
    private var resolvedRoot: String? = null

    suspend fun getCapacity(): CloudServerCapacity = withContext(Dispatchers.IO) {
        try {
            send("GET", "api/cloud-servers/capacity", body = null) { text ->
                parseCapacity(JSONObject(text))
            }
        } catch (_: Exception) {
            CloudServerCapacity(
                used = 0,
                max = 2,
                allowedVersions = DEFAULT_VERSIONS,
                canCreate = false,
                isWhitelisted = isLocallyWhitelisted(
                    AppContainer.multiplayerAuth.current()?.user?.username
                )
            )
        }
    }

    suspend fun listMine(): List<CloudServerInfo> = withContext(Dispatchers.IO) {
        send("GET", "api/cloud-servers/mine", body = null) { text ->
            parseList(text)
        }
    }

    suspend fun create(gameVersion: String, name: String? = null): CloudServerInfo =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("gameVersion", gameVersion.trim())
            if (!name.isNullOrBlank()) {
                payload.put("name", name.trim())
            }
            send("POST", "api/cloud-servers", body = payload.toString()) { text ->
                parseInfo(JSONObject(text))
            }
        }

    suspend fun get(instanceId: String): CloudServerInfo = withContext(Dispatchers.IO) {
        val id = instanceId.trim()
        require(id.isNotEmpty()) { "instanceId blank" }
        send("GET", "api/cloud-servers/${encode(id)}", body = null) { text ->
            parseInfo(JSONObject(text))
        }
    }

    suspend fun stop(instanceId: String): CloudServerInfo = withContext(Dispatchers.IO) {
        val id = instanceId.trim()
        require(id.isNotEmpty()) { "instanceId blank" }
        send("POST", "api/cloud-servers/${encode(id)}/stop", body = null) { text ->
            parseInfo(JSONObject(text))
        }
    }

    suspend fun heartbeat(instanceId: String): CloudServerInfo = withContext(Dispatchers.IO) {
        val id = instanceId.trim()
        require(id.isNotEmpty()) { "instanceId blank" }
        send("POST", "api/cloud-servers/${encode(id)}/heartbeat", body = null) { text ->
            parseInfo(JSONObject(text))
        }
    }

    /**
     * Poll until Running, or fail on terminal status / timeout (~4 min, 1.5s interval).
     */
    suspend fun waitUntilRunning(
        instanceId: String,
        timeoutMs: Long = TimeUnit.MINUTES.toMillis(4),
        onProgress: ((CloudServerInfo) -> Unit)? = null
    ): CloudServerInfo {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val info = get(instanceId)
            onProgress?.invoke(info)
            if (info.isRunning) return info
            if (info.isTerminalFailure) {
                throw CloudServerApiException(
                    code = "START_FAILED",
                    message = info.errorMessage?.takeIf { it.isNotBlank() }
                        ?: "云服启动失败（${info.status}）。",
                    statusCode = 500
                )
            }
            delay(1500)
        }
        throw CloudServerApiException(
            code = "START_TIMEOUT",
            message = "等待云服就绪超时。",
            statusCode = 408
        )
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun <T> send(
        method: String,
        relativePath: String,
        body: String?,
        parse: (String) -> T
    ): T {
        val token = accessTokenProvider()?.trim().orEmpty()
        if (token.isEmpty()) {
            throw CloudServerApiException("UNAUTHORIZED", "请先登录联机账号。", 401)
        }

        val roots = buildList {
            resolvedRoot?.let { add(it) }
            API_ROOTS.forEach { root ->
                if (root != resolvedRoot) add(root)
            }
        }

        var lastError: Throwable? = null
        for (root in roots) {
            try {
                val url = "${root.trimEnd('/')}/$relativePath"
                val builder = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", HttpClients.USER_AGENT)
                    .header("Accept", "application/json")

                when (method.uppercase()) {
                    "GET" -> builder.get()
                    "POST" -> {
                        if (body != null) {
                            builder.post(body.toRequestBody(JSON))
                        } else {
                            builder.post(EMPTY_BODY)
                        }
                    }
                    else -> error("unsupported method $method")
                }

                val request = builder.build()
                val client = if (url.startsWith("http://", ignoreCase = true)) {
                    HttpClients.cleartextRoom
                } else {
                    HttpClients.shared
                }

                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw parseApiError(raw, response.code)
                    }
                    if (raw.isBlank()) {
                        throw CloudServerApiException("EMPTY", "云服接口返回空响应。", response.code)
                    }
                    val result = parse(raw)
                    resolvedRoot = root
                    return result
                }
            } catch (ex: CloudServerApiException) {
                throw ex
            } catch (ex: Exception) {
                lastError = ex
            }
        }

        throw CloudServerApiException(
            code = "UNREACHABLE",
            message = lastError?.message ?: "无法连接云服控制面。",
            statusCode = 0
        )
    }

    private fun parseApiError(raw: String, statusCode: Int): CloudServerApiException {
        var code = "HTTP_ERROR"
        var message = raw
        runCatching {
            val err = JSONObject(raw)
            code = err.optString("code").ifBlank { code }
            message = err.optString("message").ifBlank {
                err.optString("error").ifBlank { message }
            }
        }
        if (message.isBlank()) {
            message = "HTTP $statusCode"
        }
        return CloudServerApiException(code, message, statusCode)
    }

    private fun parseCapacity(o: JSONObject): CloudServerCapacity {
        val versions = mutableListOf<String>()
        val arr = o.optJSONArray("allowedVersions")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim()
                if (v.isNotEmpty()) versions.add(v)
            }
        }
        return CloudServerCapacity(
            used = o.optInt("used", 0),
            max = o.optInt("max", 2),
            allowedVersions = versions.ifEmpty { DEFAULT_VERSIONS },
            canCreate = o.optBoolean("canCreate", false),
            isWhitelisted = o.optBoolean("isWhitelisted", false)
        )
    }

    private fun parseList(text: String): List<CloudServerInfo> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == "null") return emptyList()
        val arr = JSONArray(trimmed)
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(parseInfo(item))
            }
        }
    }

    private fun parseInfo(o: JSONObject): CloudServerInfo {
        return CloudServerInfo(
            instanceId = o.optString("instanceId").trim(),
            ownerUsername = o.optString("ownerUsername").trim(),
            name = o.optString("name").takeIf { it.isNotBlank() && it != "null" },
            gameVersion = o.optString("gameVersion").trim(),
            host = o.optString("host").trim(),
            port = o.optInt("port", 0),
            address = o.optString("address").trim(),
            status = o.optString("status").trim(),
            errorMessage = o.optString("errorMessage").takeIf { it.isNotBlank() && it != "null" },
            createdAtUtc = o.optString("createdAtUtc").takeIf { it.isNotBlank() && it != "null" },
            expiresAtUtc = o.optString("expiresAtUtc").takeIf { it.isNotBlank() && it != "null" },
            lastHeartbeatUtc = o.optString("lastHeartbeatUtc").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
