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
    val selected: Boolean = false
)

data class LauncherSession(
    val selectedVersionId: String? = null,
    val selectedAccountId: String? = null
)
