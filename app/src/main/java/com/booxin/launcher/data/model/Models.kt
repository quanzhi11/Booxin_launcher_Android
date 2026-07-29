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
    val url: String? = null
)

enum class AccountType {
    MICROSOFT,
    OFFLINE
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
    /** Minecraft --userType: msa / legacy */
    val userType: String = if (type == AccountType.MICROSOFT) "msa" else "legacy",
    val hasMinecraft: Boolean = type != AccountType.MICROSOFT
)

data class LauncherSession(
    val selectedVersionId: String? = null,
    val selectedAccountId: String? = null
)
