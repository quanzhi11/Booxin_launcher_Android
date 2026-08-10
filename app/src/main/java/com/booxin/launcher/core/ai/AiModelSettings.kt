package com.booxin.launcher.core.ai

import com.booxin.launcher.core.LauncherPrefs

class AiModelSettings {
    private var accessTier: AiMemberTier = AiMemberTier.NONE

    var selectedProvider: AiModelProvider
        get() = AiModelProfiles.parseProvider(
            LauncherPrefs.getString(KEY_PROVIDER, "Auto"),
            accessTier,
            isCustomReady
        )
        set(value) {
            LauncherPrefs.putString(KEY_PROVIDER, AiModelProfiles.toConfigValue(value))
        }

    var selectedMode: AiModeQuality
        get() = AiModelProfiles.parseMode(
            LauncherPrefs.getString(KEY_MODE, "Medium"),
            accessTier
        )
        set(value) {
            LauncherPrefs.putString(KEY_MODE, AiModelProfiles.modeConfig(value))
        }

    var customBaseUrl: String
        get() = LauncherPrefs.getString(KEY_CUSTOM_URL, "") ?: ""
        set(value) {
            LauncherPrefs.putString(KEY_CUSTOM_URL, value.trim())
        }

    var customApiKey: String
        get() = LauncherPrefs.getString(KEY_CUSTOM_KEY, "") ?: ""
        set(value) {
            LauncherPrefs.putString(KEY_CUSTOM_KEY, value.trim())
        }

    var customModelId: String
        get() = LauncherPrefs.getString(KEY_CUSTOM_MODEL, "gpt-4o-mini")?.ifBlank { "gpt-4o-mini" }
            ?: "gpt-4o-mini"
        set(value) {
            LauncherPrefs.putString(KEY_CUSTOM_MODEL, value.trim().ifBlank { "gpt-4o-mini" })
        }

    var agentEnabled: Boolean
        get() = LauncherPrefs.getString(KEY_AGENT, "0") == "1"
        set(value) {
            LauncherPrefs.putString(KEY_AGENT, if (value) "1" else "0")
        }

    var webSearchEnabled: Boolean
        get() = LauncherPrefs.getString(KEY_WEB, "1") != "0"
        set(value) {
            LauncherPrefs.putString(KEY_WEB, if (value) "1" else "0")
        }

    val isCustomReady: Boolean
        get() = customBaseUrl.isNotBlank() && customApiKey.isNotBlank()

    fun updateAccessTier(tier: AiMemberTier) {
        accessTier = tier
    }

    fun resolveRequestProfile(): AiModelProfile {
        var provider = selectedProvider
        if (provider == AiModelProvider.AUTO) {
            provider = AiModelProfiles.resolveAutoProvider(accessTier, selectedMode)
        }
        if (provider == AiModelProvider.CUSTOM && isCustomReady) {
            val base = AiModelProfiles.get(AiModelProvider.CUSTOM, accessTier, true)
            return base.copy(modelId = customModelId)
        }
        if (provider == AiModelProvider.CUSTOM) {
            provider = AiModelProfiles.resolveAutoProvider(accessTier, selectedMode)
        }
        return AiModelProfiles.get(provider, accessTier, isCustomReady)
    }

    fun displayProfiles(): List<AiModelProfile> = AiModelProfiles.all(accessTier, isCustomReady)

    companion object {
        private const val KEY_PROVIDER = "ai_model_provider"
        private const val KEY_MODE = "ai_mode_quality"
        private const val KEY_CUSTOM_URL = "ai_custom_base_url"
        private const val KEY_CUSTOM_KEY = "ai_custom_api_key"
        private const val KEY_CUSTOM_MODEL = "ai_custom_model_id"
        private const val KEY_AGENT = "ai_agent_enabled"
        private const val KEY_WEB = "ai_web_search_enabled"
    }
}
