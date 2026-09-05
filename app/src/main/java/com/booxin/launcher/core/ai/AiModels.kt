package com.booxin.launcher.core.ai

enum class AiModelProvider {
    AUTO, KIMI, DEEPSEEK_V4_PRO, GLM, CHATGPT_55, GROK_43, CUSTOM
}

enum class AiModeQuality { LOW, MEDIUM, HIGH }

enum class AiMemberTier { NONE, PRO, PRO_MAX, ULTRA }

enum class AiQuotaType { CHAT, AGENT, PLAYER }

data class AiModelProfile(
    val provider: AiModelProvider,
    val displayName: String,
    val modelId: String,
    val contextWindowTokens: Int = 8192,
    val maxCompletionTokens: Int = 600,
    val locked: Boolean = false,
    val lockHint: String? = null
)

data class AiChatMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null
)

data class AiQuotaSnapshot(
    val weeklyLimitFen: Long = 0,
    val chatCostFen: Long = 0,
    val agentCostFen: Long = 0,
    val playerCostFen: Long = 0,
    val chatTokensUsed: Long = 0,
    val agentTokensUsed: Long = 0,
    val playerTokensUsed: Long = 0,
    val playerLimitFen: Long = 0,
    val isMember: Boolean = false,
    val memberTier: String = "None",
    val memberExpiresAtUtc: String? = null,
    val redemptionOnlyAccount: Boolean = false,
    val redeemedCodeCount: Int = 0,
    val periodLabel: String = "本周",
    val isPlayPassActive: Boolean = false,
    val playPassExpiresAtUtc: String? = null,
    val playPassStock: Int = 0,
    val tempQuotaFen: Long = 0,
    val tempQuotaUsedFen: Long = 0,
    val tempQuotaRemainingFen: Long = 0,
    val canClaimDailyPlayPass: Boolean = false,
    val canActivatePlayPass: Boolean = false
) {
    val regularUsedFen: Long get() = chatCostFen + agentCostFen
    val remainingFen: Long
        get() = if (isMember) {
            (weeklyLimitFen - regularUsedFen).coerceAtLeast(0)
        } else {
            (weeklyLimitFen - chatCostFen - agentCostFen - playerCostFen).coerceAtLeast(0)
        }
    val usedFen: Long get() = weeklyLimitFen - remainingFen
    val tierLabel: String
        get() = when (memberTier) {
            "Ultra" -> "Atelier"
            "ProMax" -> "Hearth"
            "Pro" -> "Shelter"
            else -> if (isMember) "Shelter" else "免费"
        }
    val usedYuan: Double get() = usedFen / 100.0
    val weeklyLimitYuan: Double get() = weeklyLimitFen / 100.0
    val chatYuanText: String
        get() = "¥%.2f / ¥%.2f".format(chatCostFen / 100.0, weeklyLimitFen / 100.0)
    val agentYuanText: String
        get() = "¥%.2f / ¥%.2f".format(agentCostFen / 100.0, weeklyLimitFen / 100.0)

    /** PC-aligned dialogue quota line. */
    fun dialogueQuotaLine(): String = if (isMember) {
        "对话额度（本月）¥%.2f/¥%.2f".format(regularUsedFen / 100.0, weeklyLimitYuan)
    } else {
        "对话 ¥%.2f/¥%.2f（免费·本周）".format(usedYuan, weeklyLimitYuan)
    }

    fun agentQuotaLine(): String =
        "Agent ¥%.2f/¥%.2f".format(agentCostFen / 100.0, weeklyLimitYuan)

    fun playPassLine(): String = when {
        isPlayPassActive ->
            "畅玩卡使用中 · 库存 $playPassStock · 临时 ¥%.2f".format(tempQuotaRemainingFen / 100.0)
        playPassStock > 0 -> "畅玩卡未启用 · 库存 $playPassStock"
        else -> "畅玩卡 —"
    }
}

data class AiQuotaConsumeResult(
    val success: Boolean,
    val message: String = "",
    val snapshot: AiQuotaSnapshot? = null,
    val consumedTokens: Long = 0,
    val consumedCostFen: Long = 0
)

data class AiSubscriptionCreateResult(
    val success: Boolean,
    val message: String = "",
    val outTradeNo: String? = null,
    val payUrl: String? = null,
    val money: String? = null,
    val plan: String? = null
)

data class AiSubscriptionStatusResult(
    val paid: Boolean = false,
    val upgraded: Boolean = false,
    val status: String = "",
    val message: String = "",
    val snapshot: AiQuotaSnapshot? = null
)

data class AiPlayPassResult(
    val success: Boolean = false,
    val message: String = "",
    val snapshot: AiQuotaSnapshot? = null
)

data class AiChatResult(
    val message: String,
    val quotaError: Boolean = false
)

object AiModelProfiles {
    fun tierRank(tier: AiMemberTier): Int = when (tier) {
        AiMemberTier.ULTRA -> 3
        AiMemberTier.PRO_MAX -> 2
        AiMemberTier.PRO -> 1
        AiMemberTier.NONE -> 0
    }

    fun parseTier(raw: String?): AiMemberTier = when (raw?.trim()) {
        "Ultra" -> AiMemberTier.ULTRA
        "ProMax" -> AiMemberTier.PRO_MAX
        "Pro" -> AiMemberTier.PRO
        else -> AiMemberTier.NONE
    }

    fun effectiveAccessTier(tier: AiMemberTier, playPassActive: Boolean): AiMemberTier {
        if (!playPassActive) return tier
        return if (tierRank(tier) >= tierRank(AiMemberTier.PRO_MAX)) tier else AiMemberTier.PRO_MAX
    }

    fun isKimiUnlocked(tier: AiMemberTier) = tierRank(tier) >= tierRank(AiMemberTier.PRO)
    fun isChatGptUnlocked(tier: AiMemberTier) = tier == AiMemberTier.ULTRA
    fun isGrokUnlocked(tier: AiMemberTier) = tierRank(tier) >= tierRank(AiMemberTier.PRO_MAX)
    fun isHighModeUnlocked(tier: AiMemberTier) = tierRank(tier) >= tierRank(AiMemberTier.PRO_MAX)

    fun toUpstreamProviderHeader(provider: AiModelProvider): String = when (provider) {
        AiModelProvider.GLM -> "Zhipu"
        AiModelProvider.AUTO -> "Zhipu"
        AiModelProvider.DEEPSEEK_V4_PRO -> "DeepSeekV4Pro"
        AiModelProvider.KIMI -> "Kimi"
        AiModelProvider.CHATGPT_55 -> "ChatGpt55"
        AiModelProvider.GROK_43 -> "Grok43"
        AiModelProvider.CUSTOM -> "Custom"
    }

    fun toConfigValue(provider: AiModelProvider): String = when (provider) {
        AiModelProvider.DEEPSEEK_V4_PRO -> "DeepSeekV4Pro"
        AiModelProvider.GLM -> "Glm"
        AiModelProvider.CHATGPT_55 -> "ChatGpt55"
        AiModelProvider.GROK_43 -> "Grok43"
        AiModelProvider.CUSTOM -> "Custom"
        AiModelProvider.KIMI -> "Kimi"
        AiModelProvider.AUTO -> "Auto"
    }

    fun get(provider: AiModelProvider, tier: AiMemberTier, customReady: Boolean): AiModelProfile =
        when (provider) {
            AiModelProvider.AUTO -> AiModelProfile(AiModelProvider.AUTO, "Auto", "auto", 64000, 1200)
            AiModelProvider.GLM -> AiModelProfile(AiModelProvider.GLM, "GLM", "glm-4-flash", 128000, 1200)
            AiModelProvider.DEEPSEEK_V4_PRO -> AiModelProfile(
                AiModelProvider.DEEPSEEK_V4_PRO, "DeepSeek V4 Flash", "deepseek-v4-flash", 1_000_000, 1200
            )
            AiModelProvider.KIMI -> {
                val ok = isKimiUnlocked(tier)
                AiModelProfile(
                    AiModelProvider.KIMI, "KIMI", "moonshot-v1-8k", 8192, 600,
                    locked = !ok, lockHint = if (ok) null else "需 Shelter 及以上"
                )
            }
            AiModelProvider.CHATGPT_55 -> {
                val ok = isChatGptUnlocked(tier)
                AiModelProfile(
                    AiModelProvider.CHATGPT_55, "ChatGPT 5.5", "gpt-5.5", 128000, 4096,
                    locked = !ok, lockHint = if (ok) null else "需 Atelier"
                )
            }
            AiModelProvider.GROK_43 -> {
                val ok = isGrokUnlocked(tier)
                AiModelProfile(
                    AiModelProvider.GROK_43, "Grok 4.3", "grok-4.3", 1_000_000, 8192,
                    locked = !ok, lockHint = if (ok) null else "需 Hearth"
                )
            }
            AiModelProvider.CUSTOM -> AiModelProfile(
                AiModelProvider.CUSTOM, "自定义 API", "custom", 128000, 4096,
                locked = !customReady,
                lockHint = if (customReady) null else "请先在设置中填写地址与 API Key"
            )
        }

    fun all(tier: AiMemberTier, customReady: Boolean): List<AiModelProfile> = listOf(
        get(AiModelProvider.AUTO, tier, customReady),
        get(AiModelProvider.GLM, tier, customReady),
        get(AiModelProvider.DEEPSEEK_V4_PRO, tier, customReady),
        get(AiModelProvider.KIMI, tier, customReady),
        get(AiModelProvider.CHATGPT_55, tier, customReady),
        get(AiModelProvider.GROK_43, tier, customReady),
        get(AiModelProvider.CUSTOM, tier, customReady)
    )

    fun resolveAutoProvider(tier: AiMemberTier, mode: AiModeQuality): AiModelProvider {
        if (mode == AiModeQuality.HIGH && isChatGptUnlocked(tier)) return AiModelProvider.CHATGPT_55
        if (mode == AiModeQuality.HIGH && isHighModeUnlocked(tier)) return AiModelProvider.DEEPSEEK_V4_PRO
        return AiModelProvider.GLM
    }

    fun modeTuning(mode: AiModeQuality, base: AiModelProfile): Pair<Int, Double> {
        val cap = base.maxCompletionTokens.coerceAtLeast(200)
        return when (mode) {
            AiModeQuality.LOW -> minOf(cap, 400) to 0.5
            AiModeQuality.HIGH -> maxOf(cap, minOf(cap * 2, 4096)) to 0.8
            AiModeQuality.MEDIUM -> cap to 0.7
        }
    }

    fun parseProvider(value: String?, tier: AiMemberTier, customReady: Boolean): AiModelProvider {
        val v = value?.trim().orEmpty()
        return when {
            v.equals("Auto", true) || v.equals("auto", true) -> AiModelProvider.AUTO
            v.equals("Custom", true) -> if (customReady) AiModelProvider.CUSTOM else AiModelProvider.AUTO
            v.equals("ChatGpt55", true) || v.contains("gpt", true) ->
                if (isChatGptUnlocked(tier)) AiModelProvider.CHATGPT_55 else AiModelProvider.AUTO
            v.equals("Grok43", true) || v.contains("grok", true) ->
                if (isGrokUnlocked(tier)) AiModelProvider.GROK_43 else AiModelProvider.AUTO
            v.equals("Glm", true) || v.equals("GLM", true) || v.contains("glm-4", true) ->
                AiModelProvider.GLM
            v.equals("DeepSeekV4Pro", true) || v.contains("deepseek", true) ->
                AiModelProvider.DEEPSEEK_V4_PRO
            v.equals("Kimi", true) || v.contains("moonshot", true) ->
                if (isKimiUnlocked(tier)) AiModelProvider.KIMI else AiModelProvider.AUTO
            else -> AiModelProvider.AUTO
        }
    }

    fun parseMode(value: String?, tier: AiMemberTier): AiModeQuality = when {
        value.equals("High", true) ->
            if (isHighModeUnlocked(tier)) AiModeQuality.HIGH else AiModeQuality.MEDIUM
        value.equals("Low", true) -> AiModeQuality.LOW
        else -> AiModeQuality.MEDIUM
    }

    fun modeConfig(mode: AiModeQuality): String = when (mode) {
        AiModeQuality.LOW -> "Low"
        AiModeQuality.HIGH -> "High"
        AiModeQuality.MEDIUM -> "Medium"
    }
}
