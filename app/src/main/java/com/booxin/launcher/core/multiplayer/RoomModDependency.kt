package com.booxin.launcher.core.multiplayer

/**
 * Aligns with PC [RoomModDependency] / [RoomHostDependencyInfo].
 * Published to room directory as ModsJson so guests can one-click sync.
 */
data class RoomModDependency(
    val name: String = "",
    /** modrinth | curseforge */
    val source: String = "",
    val projectId: String? = null,
    val versionId: String? = null,
    val pageUrl: String? = null,
    val downloadUrl: String? = null,
    val fileName: String? = null,
    val sha1: String? = null
) {
    val hasDownloadSource: Boolean
        get() = !downloadUrl.isNullOrBlank() ||
            (source.isNotBlank() && !projectId.isNullOrBlank())
}

data class RoomHostDependencyInfo(
    val gameVersion: String = "",
    val loader: String? = null,
    val mods: List<RoomModDependency> = emptyList()
) {
    val isVanilla: Boolean get() = mods.isEmpty()

    val summaryText: String
        get() {
            val version = gameVersion.ifBlank { "未知版本" }
            if (isVanilla) return "$version · 原版无模组"
            val loaderPart = loader?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            return "$version$loaderPart · ${mods.size} 个模组"
        }
}

data class RoomDependencySnapshot(
    val modpackUrl: String? = null,
    val gameVersion: String? = null,
    val loader: String? = null,
    val mods: List<RoomModDependency> = emptyList()
) {
    val hasDownloadableContent: Boolean
        get() = mods.isNotEmpty() ||
            !modpackUrl.isNullOrBlank() ||
            !gameVersion.isNullOrBlank()
}

data class RoomSessionProfileMarker(
    val fingerprint: String = "",
    val gameVersion: String = "",
    val loader: String? = null,
    val modCount: Int = 0,
    val createdAtUtc: String = ""
)
