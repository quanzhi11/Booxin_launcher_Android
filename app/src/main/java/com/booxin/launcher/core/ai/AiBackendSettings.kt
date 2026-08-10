package com.booxin.launcher.core.ai

object AiBackendSettings {
    const val CLIENT_ACCESS_KEY = "booxin-launcher-client-v3322"
    const val PROVIDER_HEADER = "X-AI-Provider"
    const val CHAT_PATH = "/api/ai/chat/completions"
    const val REALTIME_WS_PATH = "/api/ai/realtime/ws"

    // Domain only — IP is pinned in ResilientDns (HTTPS to raw IP fails cert host check).
    val baseUrlCandidates = listOf(
        "https://boonix.art/ai-api"
    )

    fun buildBooxinQuotaKey(userId: String): String {
        val n = userId.trim().replace("-", "").lowercase()
        return "booxin:$n"
    }
}
