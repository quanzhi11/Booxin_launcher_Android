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
                "-fabric-" in lowered || lowered.startsWith("fabric-loader-") -> FABRIC
                "-quilt-" in lowered || lowered.startsWith("quilt-loader-") -> QUILT
                else -> ANY
            }
        }
    }
}

data class ModrinthSearchPage(
    val projects: List<ModrinthProject>,
    val offset: Int,
    val limit: Int,
    val totalHits: Int
) {
    val hasNext: Boolean get() = offset + projects.size < totalHits
    val hasPrevious: Boolean get() = offset > 0
    val pageNumber: Int get() = (offset / limit.coerceAtLeast(1)) + 1
    val totalPages: Int get() = if (totalHits <= 0) 1 else ((totalHits + limit - 1) / limit).coerceAtLeast(1)
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
    val loaders: List<String> = emptyList(),
    /** Full project body from /v2/project — may be markdown. */
    val body: String? = null
)

data class ModrinthVersionFile(
    val url: String,
    val filename: String,
    val primary: Boolean,
    val size: Long,
    val sha1: String? = null
)

enum class ModrinthDependencyType {
    REQUIRED,
    OPTIONAL,
    INCOMPATIBLE,
    EMBEDDED,
    UNKNOWN;

    companion object {
        fun fromApi(value: String): ModrinthDependencyType = when (value.lowercase()) {
            "required" -> REQUIRED
            "optional" -> OPTIONAL
            "incompatible" -> INCOMPATIBLE
            "embedded" -> EMBEDDED
            else -> UNKNOWN
        }
    }
}

data class ModrinthDependency(
    val projectId: String?,
    val versionId: String?,
    val fileName: String?,
    val type: ModrinthDependencyType
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
    val files: List<ModrinthVersionFile>,
    val dependencies: List<ModrinthDependency> = emptyList()
) {
    val primaryFile: ModrinthVersionFile?
        get() = files.firstOrNull { it.primary } ?: files.firstOrNull()

    val requiredDependencies: List<ModrinthDependency>
        get() = dependencies.filter { it.type == ModrinthDependencyType.REQUIRED && !it.projectId.isNullOrBlank() }
}

data class ModrinthResolvedDependency(
    val projectId: String,
    val title: String,
    val slug: String,
    val iconUrl: String?,
    val description: String,
    val versionId: String?
)

data class InstallTargetRecommendation(
    val versionId: String,
    val reason: String,
    val recommended: Boolean
)
