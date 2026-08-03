package com.booxin.launcher.core.download.modloader

import com.booxin.launcher.core.download.BmclApiDownloadProvider
import com.booxin.launcher.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

data class OptiFineBuild(
    val mcVersion: String,
    val type: String,
    val patch: String,
    val filename: String,
    val forgeHint: String? = null,
    val versionId: String,
    val recommended: Boolean = false
) {
    val displayName: String
        get() = buildString {
            append("$mcVersion · OptiFine $type $patch")
            if (!forgeHint.isNullOrBlank()) append("（$forgeHint）")
        }
}

class OptiFineVersionClient {
    suspend fun listBuilds(mcVersion: String): Result<List<OptiFineBuild>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${BmclApiDownloadProvider.DEFAULT_API_ROOT}/optifine/$mcVersion"
            parseList(mcVersion, executeGet(url))
        }
    }

    fun downloadUrls(mcVersion: String, type: String, patch: String): List<String> = listOf(
        "${BmclApiDownloadProvider.DEFAULT_API_ROOT}/optifine/$mcVersion/$type/$patch"
    )

    private fun parseList(mcVersion: String, body: String): List<OptiFineBuild> {
        val root = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val builds = buildList {
            for (i in 0 until root.length()) {
                val item = root.optJSONObject(i) ?: continue
                val type = item.optString("type").trim()
                val patch = item.optString("patch").trim()
                if (type.isBlank() || patch.isBlank()) continue
                val itemMc = item.optString("mcversion").ifBlank { item.optString("mcVersion") }
                if (itemMc.isNotBlank() && itemMc != mcVersion) continue
                val filename = item.optString("filename").ifBlank {
                    "OptiFine_${mcVersion}_${type}_$patch.jar"
                }
                val preview = filename.startsWith("preview_", ignoreCase = true) ||
                    type.contains("pre", ignoreCase = true) ||
                    patch.contains("pre", ignoreCase = true)
                add(
                    OptiFineBuild(
                        mcVersion = mcVersion,
                        type = type,
                        patch = patch,
                        filename = filename,
                        forgeHint = item.optString("forge").takeIf { it.isNotBlank() },
                        versionId = optiFineVersionId(mcVersion, type, patch),
                        recommended = !preview
                    )
                )
            }
        }
        return builds
            .distinctBy { "${it.type}_${it.patch}" }
            .sortedWith(
                compareByDescending<OptiFineBuild> { it.recommended }
                    .thenByDescending { it.type }
                    .thenByDescending { it.patch }
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
        fun optiFineVersionId(mcVersion: String, type: String, patch: String): String =
            "$mcVersion-optifine-${type}_$patch"
    }
}
