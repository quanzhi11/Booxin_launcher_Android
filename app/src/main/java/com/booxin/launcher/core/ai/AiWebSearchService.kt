package com.booxin.launcher.core.ai

import com.booxin.launcher.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class AiWebSearchService {
    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext ""
        val q = buildQuery(query)
        runCatching { searchInstant(q) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { searchHtmlLite(q) }.getOrDefault("")
    }

    private fun buildQuery(userMessage: String): String {
        var cleaned = userMessage.trim()
            .replace(Regex("^(请|帮我|帮忙|搜索|查一下|查询|联网)"), "")
            .trim()
        val lower = cleaned.lowercase()
        if (!lower.contains("minecraft") && !lower.contains("mc") &&
            !lower.contains("forge") && !lower.contains("fabric")
        ) {
            cleaned += " Minecraft"
        }
        return cleaned
    }

    private fun searchInstant(query: String): String {
        val url =
            "https://api.duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                "&format=json&no_html=1&skip_disambig=1"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.USER_AGENT)
            .get()
            .build()
        val json = HttpClients.cascadeAttempt.newCall(req).execute().use {
            it.body?.string().orEmpty()
        }
        val root = JSONObject(json)
        val sb = StringBuilder()
        val abstract = root.optString("AbstractText")
        if (abstract.isNotBlank()) {
            sb.appendLine(abstract)
            val src = root.optString("AbstractURL")
            if (src.isNotBlank()) sb.appendLine("来源: $src")
        }
        val topics = root.optJSONArray("RelatedTopics") ?: JSONArray()
        var count = 0
        for (i in 0 until topics.length()) {
            if (count >= 3) break
            val topic = topics.optJSONObject(i) ?: continue
            val text = topic.optString("Text")
            if (text.isNotBlank()) {
                sb.appendLine("- $text")
                count++
            }
        }
        return sb.toString().trim()
    }

    private fun searchHtmlLite(query: String): String {
        val url =
            "https://lite.duckduckgo.com/lite/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.USER_AGENT)
            .get()
            .build()
        val html = HttpClients.cascadeAttempt.newCall(req).execute().use {
            it.body?.string().orEmpty()
        }
        val matches = Regex(
            """class="result-link"[^>]*>([^<]+)</a>.*?<td[^>]*class="result-snippet"[^>]*>(.*?)</td>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html).take(4)
        val sb = StringBuilder()
        for (m in matches) {
            val title = m.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            val snip = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            if (title.isNotBlank()) {
                sb.appendLine("- $title")
                if (snip.isNotBlank()) sb.appendLine("  $snip")
            }
        }
        return sb.toString().trim()
    }
}
