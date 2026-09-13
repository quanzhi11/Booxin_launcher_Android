package com.booxin.launcher.core.download.modloader

import com.booxin.launcher.core.download.BmclApiDownloadProvider
import com.booxin.launcher.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

data class QuiltBuild(
    val mcVersion: String,
    val loaderVersion: String,
    val versionId: String,
    val recommended: Boolean = false
) {
    val displayName: String get() = "$mcVersion · Quilt $loaderVersion"
}

class QuiltVersionClient {
    suspend fun listBuilds(mcVersion: String): Result<List<QuiltBuild>> = withContext(Dispatchers.IO) {
        runCatching {
            var lastError: Throwable? = null
            for (url in loaderListUrls(mcVersion)) {
                val builds = runCatching { parseLoaderList(mcVersion, executeGet(url)) }
                    .onFailure { lastError = it }
                    .getOrNull()
                if (!builds.isNullOrEmpty()) return@runCatching builds
            }
            throw lastError ?: IllegalStateException("未获取到 Quilt Loader 版本")
        }
    }

    fun profileUrls(mcVersion: String, loaderVersion: String): List<String> = listOf(
        // BMCL quilt-meta is often unavailable; keep as first try then official.
        "${BmclApiDownloadProvider.DEFAULT_API_ROOT}/quilt-meta/v3/versions/loader/$mcVersion/$loaderVersion/profile/json",
        "https://meta.quiltmc.org/v3/versions/loader/$mcVersion/$loaderVersion/profile/json"
    )

    private fun loaderListUrls(mcVersion: String): List<String> = listOf(
        "${BmclApiDownloadProvider.DEFAULT_API_ROOT}/quilt-meta/v3/versions/loader/$mcVersion",
        "https://meta.quiltmc.org/v3/versions/loader/$mcVersion"
    )

    private fun parseLoaderList(mcVersion: String, body: String): List<QuiltBuild> {
        val root = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val builds = buildList {
            for (i in 0 until root.length()) {
                val item = root.optJSONObject(i) ?: continue
                val loader = item.optJSONObject("loader") ?: continue
                val version = loader.optString("version").trim()
                if (version.isBlank()) continue
                val beta = version.contains("beta", ignoreCase = true) ||
                    version.contains("alpha", ignoreCase = true)
                val stable = loader.optBoolean("stable", false) || !beta
                add(
                    QuiltBuild(
                        mcVersion = mcVersion,
                        loaderVersion = version,
                        versionId = quiltVersionId(mcVersion, version),
                        recommended = stable && !beta
                    )
                )
            }
        }
        return builds
            .distinctBy { it.loaderVersion }
            .sortedWith(
                compareByDescending<QuiltBuild> { it.recommended }
                    .thenByDescending(VERSION_COMPARATOR) { it.loaderVersion }
            )
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
        fun quiltVersionId(mcVersion: String, loaderVersion: String): String =
            com.booxin.launcher.core.version.VersionInstanceNameGenerator.quilt(mcVersion, loaderVersion)

        private val VERSION_COMPARATOR = Comparator<String> { a, b ->
            compareVersionParts(a, b)
        }

        private fun compareVersionParts(a: String, b: String): Int {
            val pa = a.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
            val pb = b.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val diff = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return a.compareTo(b)
        }
    }
}
