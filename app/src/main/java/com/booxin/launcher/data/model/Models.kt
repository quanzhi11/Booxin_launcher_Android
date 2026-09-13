package com.booxin.launcher.data.model

enum class VersionType {
    RELEASE,
    SNAPSHOT,
    OLD_BETA,
    OLD_ALPHA
}

data class GameVersion(
    val id: String,
    val type: VersionType,
    val installed: Boolean = false,
    val releaseTime: String? = null,
    /** Mojang/BMCL version.json URL from the manifest. */
    val url: String? = null,
    /** Launcher-only display alias (does not rename versions folder). */
    val customDisplayName: String? = null,
    /** User sort index; lower = higher in list. Null = unordered. */
    val sortIndex: Int? = null,
    /** Launcher-only default instance badge. */
    val isDefault: Boolean = false
) {
    /** PC-aligned: CustomDisplayName ?: VersionId */
    val displayName: String
        get() = customDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: id
}

enum class AccountType {
    MICROSOFT,
    OFFLINE,
    /** Yggdrasil / authlib-injector (LittleSkin, Ely.by, …). */
    THIRD_PARTY
}

data class LauncherAccount(
    val id: String,
    val name: String,
    val type: AccountType,
    val selected: Boolean = false,
    /** Undashed UUID for microsoft; offline may be null until launch. */
    val uuid: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accessTokenExpiresAtMs: Long? = null,
    val xuid: String? = null,
    /** Minecraft --userType: msa / legacy / mojang (third-party). */
    val userType: String = when (type) {
        AccountType.MICROSOFT -> "msa"
        AccountType.THIRD_PARTY -> "mojang"
        AccountType.OFFLINE -> "legacy"
    },
    val hasMinecraft: Boolean = type != AccountType.MICROSOFT,
    /** Absolute path to imported / fetched offline skin PNG, if any. */
    val skinPath: String? = null,
    /**
     * Offline skin strategy (PC-aligned): random / steve / alex / player / custom.
     * Empty or blank → inferred from [skinPath] (legacy custom import).
     */
    val skinMode: String? = null,
    /** classic / slim — used by custom & player modes. */
    val skinModel: String = "classic",
    /** Mojang player name when [skinMode] is player. */
    val skinPlayerName: String? = null,
    /** Mojang UUID (no dashes) when [skinMode] is player. */
    val skinPlayerUuid: String? = null,
    /** Yggdrasil API root for [AccountType.THIRD_PARTY]. */
    val thirdPartyServerUrl: String? = null
)

data class LauncherSession(
    val selectedVersionId: String? = null,
    val selectedAccountId: String? = null
)
