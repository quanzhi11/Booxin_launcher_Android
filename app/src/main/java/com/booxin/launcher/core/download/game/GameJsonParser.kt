package com.booxin.launcher.core.download.game

import com.booxin.launcher.data.model.VersionType
import org.json.JSONArray
import org.json.JSONObject

data class RemoteVersionInfo(
    val id: String,
    val type: VersionType,
    val url: String,
    val releaseTime: String?
)

data class VersionManifest(
    val latestRelease: String?,
    val latestSnapshot: String?,
    val versions: List<RemoteVersionInfo>
)

data class ArtifactDownload(
    val url: String,
    val sha1: String?,
    val size: Long,
    val path: String?
)

data class AssetIndexInfo(
    val id: String,
    val url: String,
    val sha1: String?,
    val size: Long,
    val totalSize: Long
)

data class ResolvedLibrary(
    val name: String,
    val path: String,
    val url: String,
    val sha1: String?,
    val size: Long
)

data class ResolvedVersion(
    val id: String,
    val mainClass: String?,
    val assetIndex: AssetIndexInfo?,
    val client: ArtifactDownload?,
    val libraries: List<ResolvedLibrary>,
    val rawJson: String
)

data class AssetObject(
    val hash: String,
    val size: Long
) {
    val hashPath: String get() = "${hash.substring(0, 2)}/$hash"
}

object GameJsonParser {

    fun parseManifest(json: String): VersionManifest {
        val root = JSONObject(json)
        val latest = root.optJSONObject("latest")
        val versions = root.getJSONArray("versions")
        val list = ArrayList<RemoteVersionInfo>(versions.length())
        for (i in 0 until versions.length()) {
            val item = versions.getJSONObject(i)
            list += RemoteVersionInfo(
                id = item.getString("id"),
                type = mapType(item.optString("type")),
                url = item.getString("url"),
                releaseTime = item.optString("releaseTime").ifBlank { null }
            )
        }
        return VersionManifest(
            latestRelease = latest?.optString("release"),
            latestSnapshot = latest?.optString("snapshot"),
            versions = list
        )
    }

    fun parseVersionJson(json: String): ResolvedVersion {
        val root = JSONObject(json)
        val id = root.getString("id")
        val mainClass = root.optString("mainClass").ifBlank { null }

        val downloads = root.optJSONObject("downloads")
        val clientObj = downloads?.optJSONObject("client")
        val client = clientObj?.let {
            ArtifactDownload(
                url = it.getString("url"),
                sha1 = it.optString("sha1").ifBlank { null },
                size = it.optLong("size", 0L),
                path = null
            )
        }

        val assetIndexObj = root.optJSONObject("assetIndex")
        val assetIndex = assetIndexObj?.let {
            AssetIndexInfo(
                id = it.getString("id"),
                url = it.getString("url"),
                sha1 = it.optString("sha1").ifBlank { null },
                size = it.optLong("size", 0L),
                totalSize = it.optLong("totalSize", 0L)
            )
        }

        val libraries = resolveLibraries(root.optJSONArray("libraries") ?: JSONArray())
        return ResolvedVersion(
            id = id,
            mainClass = mainClass,
            assetIndex = assetIndex,
            client = client,
            libraries = libraries,
            rawJson = json
        )
    }

    fun parseAssetIndex(json: String): List<AssetObject> {
        val objects = JSONObject(json).getJSONObject("objects")
        val result = ArrayList<AssetObject>(objects.length())
        val keys = objects.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = objects.getJSONObject(key)
            result += AssetObject(
                hash = obj.getString("hash"),
                size = obj.optLong("size", 0L)
            )
        }
        return result
    }

    private fun resolveLibraries(array: JSONArray): List<ResolvedLibrary> {
        val result = ArrayList<ResolvedLibrary>()
        for (i in 0 until array.length()) {
            val lib = array.getJSONObject(i)
            val name = lib.getString("name")
            if (!LibraryFilter.shouldKeep(name)) continue
            if (!appliesToCurrentEnvironment(lib.optJSONArray("rules"))) continue
            // Keep the Java artifact even when the entry also lists desktop natives.
            // Android does not use those native classifiers.
            val artifact = lib.optJSONObject("downloads")?.optJSONObject("artifact")
            if (artifact == null && lib.has("natives")) continue

            val path = artifact?.optString("path")?.ifBlank { null } ?: mavenPath(name)
            val url = when {
                artifact?.has("url") == true -> artifact.getString("url").ifBlank { "" }
                lib.has("url") -> lib.getString("url").let { base ->
                    if (base.isBlank()) ""
                    else if (base.endsWith("/")) base + path else "$base/$path"
                }
                else -> DEFAULT_LIBRARY_URL + path
            }
            result += ResolvedLibrary(
                name = name,
                path = path,
                url = url,
                sha1 = artifact?.optString("sha1")?.ifBlank { null },
                size = artifact?.optLong("size", 0L) ?: 0L
            )
        }
        return LibraryFilter.upgrade(result)
    }

    private fun appliesToCurrentEnvironment(rules: JSONArray?): Boolean {
        if (rules == null || rules.length() == 0) return true
        // FCL Android specialization: OSRestriction.allow() is always false.
        // Rules that only allow a desktop OS will disallow; allow-without-os still works.
        var allowed = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val action = rule.optString("action", "allow")
            val os = rule.optJSONObject("os")
            val matches = if (os == null) {
                true
            } else {
                // Never match desktop OS names on Android.
                false
            }
            if (matches) {
                allowed = action == "allow"
            }
        }
        return allowed
    }

    fun mavenPath(name: String): String {
        // group:artifact:version[:classifier[@extension]]
        val parts = name.split(':')
        require(parts.size >= 3) { "非法 Maven 坐标: $name" }
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifierPart = parts.getOrNull(3)
        val (classifier, extension) = if (classifierPart != null && classifierPart.contains('@')) {
            classifierPart.substringBefore('@') to classifierPart.substringAfter('@')
        } else {
            classifierPart to "jar"
        }
        val fileName = if (classifier.isNullOrBlank()) {
            "$artifact-$version.$extension"
        } else {
            "$artifact-$version-$classifier.$extension"
        }
        return "$group/$artifact/$version/$fileName"
    }

    private fun mapType(raw: String): VersionType = when (raw.lowercase()) {
        "release" -> VersionType.RELEASE
        "snapshot" -> VersionType.SNAPSHOT
        "old_beta" -> VersionType.OLD_BETA
        "old_alpha" -> VersionType.OLD_ALPHA
        else -> VersionType.SNAPSHOT
    }

    const val DEFAULT_LIBRARY_URL = "https://libraries.minecraft.net/"
}
