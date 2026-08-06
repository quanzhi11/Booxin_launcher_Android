package com.booxin.launcher.core.update

import android.util.Log
import com.booxin.launcher.BuildConfig
import com.booxin.launcher.core.net.FileDownloader
import com.booxin.launcher.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

object LauncherUpdateChecker {
    private const val TAG = "BooxinUpdate"
    const val UPDATE_URL = "https://www.boonix.art/server_update/phones.json"

    suspend fun fetch(): Result<LauncherUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            // 绕过 CDN 缓存，避免旧 phones.json。
            val url = "$UPDATE_URL?_=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.USER_AGENT)
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .get()
                .build()
            HttpClients.shared.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("请求失败 HTTP ${response.code}: $UPDATE_URL")
                }
                val body = response.body?.string() ?: throw IOException("空响应体: $UPDATE_URL")
                Log.i(TAG, "phones.json raw=${body.take(240)}")
                parse(body).also {
                    Log.i(
                        TAG,
                        "parsed remote=${it.latestVersion} code=${it.latestVersionCode} " +
                            "local=${localVersionName()} code=${localVersionCode()}"
                    )
                }
            }
        }
    }

    fun parse(json: String): LauncherUpdateInfo {
        val cleaned = json.trim()
            .removePrefix("\uFEFF")
            .ifBlank { "{}" }
        val root = JSONObject(cleaned)

        val versionCode = readInt(
            root,
            "latestVersionCode",
            "versionCode",
            "code"
        )
        val versionName = readString(
            root,
            "latestVersion",
            "versionName",
            "version"
        ).ifBlank { "0.0.0" }

        return LauncherUpdateInfo(
            latestVersion = versionName,
            latestVersionCode = versionCode,
            minSupportedVersionCode = readInt(root, "minSupportedVersionCode").let {
                if (it <= 0) 1 else it
            },
            forceUpdate = root.optBoolean("forceUpdate", false) ||
                root.optBoolean("force", false),
            releaseNotes = readString(root, "releaseNotes", "changelog", "notes"),
            apkUrl = readString(root, "apkUrl", "url", "downloadUrl"),
            apkSha256 = readString(root, "apkSha256", "sha256"),
            publishedAt = readString(root, "publishedAt", "time")
        )
    }

    private fun readString(root: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (!root.has(key) || root.isNull(key)) continue
            val value = root.opt(key) ?: continue
            val text = value.toString().trim()
            if (text.isNotEmpty() && text != "null") return text
        }
        return ""
    }

    /** Accept number or numeric string ("2" / "999"). */
    private fun readInt(root: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            if (!root.has(key) || root.isNull(key)) continue
            when (val value = root.opt(key)) {
                is Number -> return value.toInt()
                is String -> {
                    val digits = value.trim().filter { it.isDigit() || it == '-' }
                    digits.toIntOrNull()?.let { return it }
                }
            }
        }
        return 0
    }

    fun localVersionCode(): Int = BuildConfig.VERSION_CODE

    fun localVersionName(): String = BuildConfig.VERSION_NAME
}
