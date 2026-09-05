package com.booxin.launcher.data.model

/** Community browse / install backend. */
enum class CommunitySource {
    MODRINTH,
    CURSEFORGE;

    val displayName: String
        get() = when (this) {
            MODRINTH -> "Modrinth"
            CURSEFORGE -> "CurseForge"
        }
}
