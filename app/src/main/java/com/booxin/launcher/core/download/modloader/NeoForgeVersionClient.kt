package com.booxin.launcher.core.download.modloader

import com.booxin.launcher.core.download.BmclApiDownloadProvider
import com.booxin.launcher.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class NeoForgeBuild(
    val mcVersion: String,
    val loaderVersion: String,
    val versionId: String,
    val recommended: Boolean = false
) {
    val displayName: String get() = "$mcVersion · NeoForge $loaderVersion"
}

class NeoForgeVersionClient {
    suspend fun listBuilds(mcVersion: String): Result<List<NeoForgeBuild>> = withContext(Dispatchers.IO) {
        runCatching {
            val fromList = runCatching {
                val url = "${BmclApiDownloadProvider.DEFAULT_API_ROOT}/neoforge/list/$mcVersion"
                parseListApi(mcVersion, executeGet(url))
            }.getOrDefault(emptyList())
            if (fromList.isNotEmpty()) return@runCatching fromList

            parseMavenDetails(mcVersion, executeGet(mavenDetailsUrl(mcVersion)))
        }
    }

    fun installerUrls(mcVersion: String, loaderVersion: String): List<String> {
        val packageName = packageName(mcVersion, loaderVersion)
        val artifact = "$packageName-$loaderVersion-installer.jar"
        val mavenPath = "net/neoforged/$packageName/$loaderVersion/$artifact"
        return listOf(
            "${BmclApiDownloadProvider.DEFAULT_API_ROOT}/maven/$mavenPath",
            "https://maven.neoforged.net/releases/$mavenPath"
        )
    }

    private fun parseListApi(mcVersion: String, body: String): List<NeoForgeBuild> {
        val root = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val builds = buildList {
            for (i in 0 until root.length()) {
                val item = root.optJSONObject(i) ?: continue
                val itemMc = item.optString("mcversion").ifBlank { item.optString("mcVersion") }
                if (itemMc.isNotBlank() && itemMc != mcVersion) continue
                val loaderVersion = resolveLoaderVersion(mcVersion, item) ?: continue
                add(
                    NeoForgeBuild(
                        mcVersion = mcVersion,
                        loaderVersion = loaderVersion,
                        versionId = neoForgeVersionId(mcVersion, loaderVersion)
                    )
                )
            }
        }
        return builds
            .distinctBy { it.loaderVersion }
            .sortedWith(compareByDescending(VERSION_COMPARATOR) { it.loaderVersion })
    }

    private fun parseMavenDetails(mcVersion: String, body: String): List<NeoForgeBuild> {
        val files = runCatching { JSONObject(body).optJSONArray("files") }.getOrNull()
            ?: return emptyList()
        val builds = buildList {
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                if (!item.optString("type").equals("DIRECTORY", ignoreCase = true)) continue
                val name = item.optString("name").trim()
                if (name.isBlank() || !isCompatible(name, mcVersion)) continue
                add(
                    NeoForgeBuild(
                        mcVersion = mcVersion,
                        loaderVersion = name,
                        versionId = neoForgeVersionId(mcVersion, name)
                    )
                )
            }
        }
        return builds
            .distinctBy { it.loaderVersion }
            .sortedWith(compareByDescending(VERSION_COMPARATOR) { it.loaderVersion })
    }

    private fun resolveLoaderVersion(mcVersion: String, item: JSONObject): String? {
        extractVersionFromInstallerPath(item.optString("installerPath").trim())?.let { return it }

        val version = item.optString("version").trim()
        val rawVersion = item.optString("rawVersion").trim().replace("-forge-", "-")

        return when {
            version.isNotBlank() && isCompatible(version, mcVersion) -> version
            rawVersion.isNotBlank() && isCompatible(rawVersion, mcVersion) -> rawVersion
            version.isNotBlank() && mcVersion == "1.20.1" -> {
                val legacy = "$mcVersion-$version"
                legacy.takeIf { isCompatible(it, mcVersion) }
            }
            else -> null
        }
    }

    private fun extractVersionFromInstallerPath(path: String): String? {
        if (path.isBlank()) return null
        // /maven/net/neoforged/neoforge/21.1.1/neoforge-21.1.1-installer.jar
        val parts = path.trim('/').split('/')
        if (parts.size < 2) return null
        return parts[parts.size - 2].takeIf { it.isNotBlank() }
    }

    private fun mavenDetailsUrl(mcVersion: String): String {
        val packageName = if (mcVersion == "1.20.1") "forge" else "neoforge"
        return "${BmclApiDownloadProvider.DEFAULT_API_ROOT}/neoforge/meta/api/maven/details/releases/net/neoforged/$packageName"
    }

    private fun packageName(mcVersion: String, loaderVersion: String): String =
        if (mcVersion == "1.20.1" || loaderVersion.startsWith("1.20.1-")) "forge" else "neoforge"

    private fun executeGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.USER_AGENT)
            .get()
            .build()
        HttpClients.shared.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: $url")
            }
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        fun neoForgeVersionId(mcVersion: String, loaderVersion: String): String =
            "$mcVersion-neoforge-$loaderVersion"

        fun isCompatible(neoForgeVersion: String, gameVersion: String): Boolean {
            if (neoForgeVersion.isBlank() || gameVersion.isBlank()) return false
            return if (neoForgeVersion.contains('-')) {
                val mcPart = neoForgeVersion.substringBefore('-')
                // Exact MC id match — startsWith would treat 1.21.11 as compatible with 1.21.1.
                gameVersion == mcPart
            } else {
                val parts = neoForgeVersion.split('.')
                if (parts.size < 2) return false
                val expectedMc = "1.${parts[0]}.${parts[1]}"
                gameVersion == expectedMc
            }
        }

        private val VERSION_COMPARATOR = Comparator<String> { a, b ->
            compareVersionParts(a, b)
        }

        private fun compareVersionParts(a: String, b: String): Int {
            // Prefer full legacy ids when comparing mixed shapes.
            if (a.contains('-') != b.contains('-')) {
                return a.contains('-').compareTo(b.contains('-'))
            }
            val cleanA = if (a.contains('-')) a.substringAfter('-') else a
            val cleanB = if (b.contains('-')) b.substringAfter('-') else b
            val pa = cleanA.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = cleanB.split('.').map { it.toIntOrNull() ?: 0 }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val diff = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return a.compareTo(b)
        }
    }
}
