package com.booxin.launcher.core.download.modloader

import com.booxin.launcher.core.download.BmclApiDownloadProvider
import com.booxin.launcher.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class ForgeBuild(
    val mcVersion: String,
    val loaderVersion: String,
    val versionId: String,
    val recommended: Boolean = false
) {
    val displayName: String get() = "$mcVersion · Forge $loaderVersion"
}

class ForgeVersionClient {
    suspend fun listBuilds(mcVersion: String): Result<List<ForgeBuild>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${BmclApiDownloadProvider.DEFAULT_API_ROOT}/forge/minecraft/$mcVersion"
            val text = executeGet(url)
            parseForgeList(mcVersion, text)
        }
    }

    /** BMCL installer 下载常 404，改走 Maven。 */
    fun installerUrls(mcVersion: String, loaderVersion: String): List<String> {
        val coordinate = "$mcVersion-$loaderVersion"
        val artifact = "forge-$coordinate-installer.jar"
        val mavenPath = "net/minecraftforge/forge/$coordinate/$artifact"
        return listOf(
            "${BmclApiDownloadProvider.DEFAULT_API_ROOT}/maven/$mavenPath",
            "https://maven.minecraftforge.net/$mavenPath"
        )
    }

    fun injectorJarUrls(): List<String> = listOf(
        "${BmclApiDownloadProvider.DEFAULT_API_ROOT}/maven/com/bangbang93/forge-installer/1.2.0/forge-installer-1.2.0.jar",
        "https://repo1.maven.org/maven2/com/bangbang93/forge-installer/1.2.0/forge-installer-1.2.0.jar"
    )

    private fun parseForgeList(mcVersion: String, body: String): List<ForgeBuild> {
        val root = runCatching { JSONArray(body) }.getOrNull()
            ?: runCatching { JSONObject(body).getJSONArray("versions") }.getOrNull()
            ?: return emptyList()

        val builds = buildList {
            for (i in 0 until root.length()) {
                when (val item = root.get(i)) {
                    is String -> add(item to false)
                    is JSONObject -> {
                        val version = item.optString("version")
                            .ifBlank { item.optString("forgeVersion") }
                            .ifBlank { item.optString("loaderVersion") }
                        if (version.isBlank()) continue
                        val recommended = item.optBoolean("recommended", false) ||
                            item.optString("type").equals("recommended", ignoreCase = true)
                        add(version to recommended)
                    }
                }
            }
        }
        return builds
            .distinctBy { it.first }
            .map { (loaderVersion, recommended) ->
                ForgeBuild(
                    mcVersion = mcVersion,
                    loaderVersion = loaderVersion,
                    versionId = forgeVersionId(mcVersion, loaderVersion),
                    recommended = recommended
                )
            }
            .sortedByDescending { it.loaderVersion }
    }

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
        fun forgeVersionId(mcVersion: String, loaderVersion: String): String =
            com.booxin.launcher.core.version.VersionInstanceNameGenerator.forge(mcVersion, loaderVersion)
    }
}
