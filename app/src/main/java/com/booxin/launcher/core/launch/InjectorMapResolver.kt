package com.booxin.launcher.core.launch

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** 从 injector_map.json 解析 -Dbooxin.injector=... */
object InjectorMapResolver {

    fun resolveArg(context: Context, versionId: String, mcVersionId: String): String? {
        val raw = runCatching {
            context.assets.open("app_runtime/injector_map.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        val maps = JSONObject(raw).optJSONArray("maps") ?: return null

        val assetId = normalizeAssetId(mcVersionId)
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until maps.length()) {
            val entry = maps.optJSONObject(i) ?: continue
            val id = entry.optString("id")
            val score = matchScore(id, assetId, mcVersionId)
            if (score > bestScore) {
                bestScore = score
                best = entry
            }
        }
        if (best == null || bestScore < 0) return null
        val argument = best.optJSONObject("argument") ?: return null
        val loaderHint = versionId.lowercase(Locale.US)
        val pick = when {
            "neoforge" in loaderHint || "neo" in loaderHint ->
                argument.optString("neoforge").ifEmpty { null }
                    ?: argument.optString("forge").ifEmpty { null }
                    ?: argument.optString("vanilla").ifEmpty { null }
            "forge" in loaderHint ->
                argument.optString("forge").ifEmpty { null }
                    ?: argument.optString("vanilla").ifEmpty { null }
            "fabric" in loaderHint || "quilt" in loaderHint ->
                argument.optString("fabric").ifEmpty { null }
                    ?: argument.optString("vanilla").ifEmpty { null }
            else ->
                argument.optString("vanilla").ifEmpty { null }
                    ?: argument.optString("fabric").ifEmpty { null }
                    ?: argument.optString("forge").ifEmpty { null }
                    ?: argument.optString("neoforge").ifEmpty { null }
        }
        return pick?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun normalizeAssetId(mcVersionId: String): String {
        val v = mcVersionId.trim()
        val m = Regex("""^(\d+)\.(\d+)""").find(v) ?: return v
        return "${m.groupValues[1]}.${m.groupValues[2]}"
    }

    private fun matchScore(mapId: String, assetId: String, mcVersionId: String): Int {
        if (mapId == assetId || mapId == mcVersionId) return 100
        if (mapId.length <= 2 && mapId.all { it.isDigit() }) {
            val minor = assetId.substringAfter('.', missingDelimiterValue = "").toIntOrNull() ?: return -1
            val major = assetId.substringBefore('.').toIntOrNull() ?: return -1
            if (major < 1) return -1
            return if (major >= 1 && minor >= 20) mapId.toIntOrNull() ?: -1 else -1
        }
        if (assetId.startsWith(mapId) || mapId.startsWith(assetId.substringBeforeLast('.'))) {
            return 50
        }
        return -1
    }
}
