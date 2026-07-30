package com.booxin.launcher.data.model

enum class CommunityContentType(
    val projectType: String,
    val targetSubdir: String
) {
    MOD("mod", "mods"),
    RESOURCE_PACK("resourcepack", "resourcepacks"),
    MODPACK("modpack", "")
}

enum class CommunityLoader(val apiValue: String?) {
    ANY(null),
    FORGE("forge"),
    NEOFORGE("neoforge"),
    FABRIC("fabric"),
    QUILT("quilt");

    companion object {
        fun fromVersionId(versionId: String): CommunityLoader {
            val lowered = versionId.lowercase()
            return when {
                "-neoforge-" in lowered -> NEOFORGE
                "-forge-" in lowered -> FORGE
                "-fabric-" in lowered -> FABRIC
                "-quilt-" in lowered -> QUILT
                else -> ANY
            }
        }
    }
}

data class ModrinthProject(
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val author: String,
    val iconUrl: String? = null,
    val downloads: Int = 0,
    val categories: List<String> = emptyList(),
    val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList()
)

data class ModrinthVersionFile(
    val url: String,
    val filename: String,
    val primary: Boolean,
    val size: Long,
    val sha1: String? = null
)

data class ModrinthProjectVersion(
    val id: String,
    val name: String,
    val versionNumber: String,
    val changelog: String?,
    val datePublished: String?,
    val versionType: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val files: List<ModrinthVersionFile>
) {
    val primaryFile: ModrinthVersionFile?
        get() = files.firstOrNull { it.primary } ?: files.firstOrNull()
}

data class InstallTargetRecommendation(
    val versionId: String,
    val reason: String,
    val recommended: Boolean
)
