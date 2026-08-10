package com.booxin.launcher.core.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AiChatService(
    private val appContext: Context,
    val modelSettings: AiModelSettings,
    val backend: AiBackendClient,
    private val webSearch: AiWebSearchService
) {
    private val history = ArrayList<AiChatMessage>()
    private var systemPrompt = DEFAULT_SYSTEM

    fun displayHistory(): List<AiChatMessage> =
        history.filter { it.role == "user" || it.role == "assistant" }

    fun clearHistory() {
        history.clear()
        persist()
    }

    fun loadHistory() {
        history.clear()
        val file = historyFile()
        if (!file.isFile) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                history.add(
                    AiChatMessage(
                        role = o.optString("role"),
                        content = o.optString("content")
                    )
                )
            }
        }
    }

    suspend fun refreshQuota(userKey: String?): AiQuotaSnapshot? {
        if (userKey.isNullOrBlank()) return null
        val snap = backend.getQuotaSnapshot(userKey) ?: return null
        val tier = AiModelProfiles.effectiveAccessTier(
            AiModelProfiles.parseTier(snap.memberTier),
            snap.isPlayPassActive
        )
        modelSettings.updateAccessTier(tier)
        return snap
    }

    suspend fun processUserMessage(
        message: String,
        userKey: String?,
        agentMode: Boolean = modelSettings.agentEnabled,
        enableWebSearch: Boolean = modelSettings.webSearchEnabled,
        onProgress: ((String) -> Unit)? = null
    ): AiChatResult {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return AiChatResult("请输入内容。")

        val usingCustom = modelSettings.selectedProvider == AiModelProvider.CUSTOM &&
            modelSettings.isCustomReady

        if (!usingCustom && !userKey.isNullOrBlank()) {
            val snap = refreshQuota(userKey)
            if (snap != null && snap.remainingFen <= 0 && snap.tempQuotaRemainingFen <= 0) {
                val msg = if (snap.isMember) {
                    "今日 ${snap.tierLabel} 额度已用完，请明天再试或升级订阅。"
                } else {
                    "本周免费额度已用完，请下周一刷新，或升级订阅获取更多额度。"
                }
                history.add(AiChatMessage("user", trimmed))
                history.add(AiChatMessage("assistant", msg))
                trimHistory()
                persist()
                return AiChatResult(msg, quotaError = true)
            }
        }

        return if (agentMode) {
            runAgent(trimmed, userKey, enableWebSearch, usingCustom, onProgress)
        } else {
            runChat(trimmed, userKey, enableWebSearch, usingCustom)
        }
    }

    private suspend fun runChat(
        message: String,
        userKey: String?,
        enableWebSearch: Boolean,
        usingCustom: Boolean
    ): AiChatResult {
        var prompt = message
        if (enableWebSearch) {
            val hits = webSearch.search(message)
            if (hits.isNotBlank()) {
                prompt = "【联网搜索结果】\n$hits\n\n【用户问题】\n$message"
            }
        }
        val reply = sendCompletion(prompt, recordUser = message, userKey = userKey, usingCustom = usingCustom)
        return AiChatResult(reply)
    }

    private suspend fun runAgent(
        message: String,
        userKey: String?,
        enableWebSearch: Boolean,
        usingCustom: Boolean,
        onProgress: ((String) -> Unit)?
    ): AiChatResult = withContext(Dispatchers.IO) {
        val profile = modelSettings.resolveRequestProfile()
        val (maxTokens, _) = AiModelProfiles.modeTuning(modelSettings.selectedMode, profile)
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", AiAgentTools.buildAgentSystemPrompt())
        )
        for (m in displayHistory().takeLast(6)) {
            messages.put(JSONObject().put("role", m.role).put("content", m.content.take(500)))
        }
        messages.put(JSONObject().put("role", "user").put("content", message.take(2000)))

        val tools = AiAgentTools.buildToolDefinitions(enableWebSearch)
        var totalCalls = 0
        var lastText = ""

        for (iter in 0 until AiAgentTools.MAX_ITERATIONS) {
            if (totalCalls >= AiAgentTools.MAX_TOOL_CALLS) break
            onProgress?.invoke("Agent 规划中（第 ${iter + 1} 轮）…")
            val body = JSONObject()
                .put("model", profile.modelId)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("temperature", 0.2)
                .put("max_tokens", maxTokens.coerceAtLeast(256))
                .put("stream", false)
                .toString()
            val raw = backend.chatCompletion(modelSettings.selectedProvider, body)
                .getOrElse { return@withContext AiChatResult("Agent 失败: ${it.message}") }
            val root = JSONObject(raw)
            val choice = root.optJSONArray("choices")?.optJSONObject(0)
            val msg = choice?.optJSONObject("message")
            val content = msg?.optString("content").orEmpty()
            val toolCalls = msg?.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                lastText = content.ifBlank { "（无回复）" }
                if (!usingCustom) consumeFromUsage(userKey, root, AiQuotaType.AGENT, profile)
                history.add(AiChatMessage("user", message))
                history.add(AiChatMessage("assistant", lastText))
                trimHistory()
                persist()
                return@withContext AiChatResult(lastText)
            }
            messages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", content)
                    .put("tool_calls", toolCalls)
            )
            for (i in 0 until toolCalls.length()) {
                if (totalCalls >= AiAgentTools.MAX_TOOL_CALLS) break
                val call = toolCalls.optJSONObject(i) ?: continue
                val id = call.optString("id")
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val args = fn.optString("arguments")
                onProgress?.invoke("执行工具: $name")
                val result = AiAgentTools.execute(name, args, webSearch).take(1800)
                totalCalls++
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", result)
                )
            }
            if (!usingCustom) consumeFromUsage(userKey, root, AiQuotaType.AGENT, profile)
            lastText = content
        }
        val fallback = lastText.ifBlank { "Agent 已达工具调用上限，请缩小问题范围后重试。" }
        history.add(AiChatMessage("user", message))
        history.add(AiChatMessage("assistant", fallback))
        trimHistory()
        persist()
        AiChatResult(fallback)
    }

    private suspend fun sendCompletion(
        prompt: String,
        recordUser: String,
        userKey: String?,
        usingCustom: Boolean
    ): String {
        val profile = modelSettings.resolveRequestProfile()
        val (maxTokens, temperature) = AiModelProfiles.modeTuning(modelSettings.selectedMode, profile)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (m in displayHistory().takeLast(16)) {
            messages.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", profile.modelId)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("top_p", 0.9)
            .put("stream", false)
            .toString()
        val raw = backend.chatCompletion(modelSettings.selectedProvider, body)
            .getOrElse { return "发送失败: ${it.message}" }
        val root = JSONObject(raw)
        val text = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
            .ifBlank {
                root.optJSONObject("error")?.optString("message")?.let { "错误: $it" }
                    ?: "抱歉，我无法生成响应，请稍后再试。"
            }
        history.add(AiChatMessage("user", recordUser))
        history.add(AiChatMessage("assistant", text))
        trimHistory()
        persist()
        if (!usingCustom) consumeFromUsage(userKey, root, AiQuotaType.CHAT, profile)
        return text
    }

    private suspend fun consumeFromUsage(
        userKey: String?,
        root: JSONObject,
        type: AiQuotaType,
        profile: AiModelProfile
    ) {
        if (userKey.isNullOrBlank()) return
        val usage = root.optJSONObject("usage")
        val prompt = usage?.optLong("prompt_tokens") ?: 0L
        val completion = usage?.optLong("completion_tokens") ?: 0L
        val total = usage?.optLong("total_tokens")
            ?: (prompt + completion).coerceAtLeast(100L)
        backend.consumeQuota(
            userKey = userKey,
            quotaType = type,
            tokens = total,
            provider = AiModelProfiles.toConfigValue(profile.provider),
            modelId = profile.modelId,
            promptTokens = prompt,
            completionTokens = completion
        )
    }

    private fun trimHistory() {
        while (history.size > 40) {
            history.removeAt(0)
        }
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            for (m in displayHistory().takeLast(40)) {
                arr.put(JSONObject().put("role", m.role).put("content", m.content))
            }
            historyFile().writeText(arr.toString())
        }
    }

    private fun historyFile(): File = File(appContext.filesDir, "ai_chat_history.json")

    companion object {
        private const val DEFAULT_SYSTEM =
            "你是 Booxin 手机 Minecraft 启动器的 AI 助手。用简体中文回答，聚焦 Minecraft、模组、崩溃与启动器使用。"
    }
}
