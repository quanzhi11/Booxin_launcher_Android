package com.booxin.launcher.core.pluginstore

import com.booxin.launcher.core.multiplayer.BooxinAuthSession
import com.booxin.launcher.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class PluginStoreApi {

    companion object {
        const val DEFAULT_ROOT = "https://boonix.art/plugin-api"
        const val MAX_UPLOAD_BYTES = 250L * 1024L * 1024L
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
    }

    suspend fun listPlugins(): Result<List<StorePlugin>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(executeGet("$DEFAULT_ROOT/api/plugins"))
            parsePlugins(root.optJSONArray("items"))
        }
    }

    suspend fun getPlugin(id: String): Result<StorePlugin> = withContext(Dispatchers.IO) {
        runCatching {
            parsePlugin(JSONObject(executeGet("$DEFAULT_ROOT/api/plugins/$id")))
        }
    }

    suspend fun listComments(
        pluginId: String,
        limit: Int = 50,
        session: BooxinAuthSession? = null
    ): Result<List<PluginComment>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$DEFAULT_ROOT/api/plugins/$pluginId/comments?limit=$limit")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val json = if (session != null) executeAuthorized(session, req) else execute(req)
                parseComments(JSONObject(json).optJSONArray("items"))
            }
        }

    suspend fun myRating(session: BooxinAuthSession, pluginId: String): Result<Int?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = executeAuthorized(
                    session,
                    Request.Builder()
                        .url("$DEFAULT_ROOT/api/plugins/$pluginId/rating/mine")
                        .get()
                        .header("Accept", "application/json")
                        .build()
                )
                val o = JSONObject(json)
                if (o.isNull("score")) null else o.optInt("score")
            }
        }

    suspend fun submitRating(
        session: BooxinAuthSession,
        pluginId: String,
        score: Int
    ): Result<StorePluginRatingResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("score", score)
            val json = executeAuthorized(
                session,
                Request.Builder()
                    .url("$DEFAULT_ROOT/api/plugins/$pluginId/rating")
                    .put(body.toString().toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .build()
            )
            val o = JSONObject(json)
            StorePluginRatingResult(
                score = o.optInt("score"),
                ratingAvg = o.optDouble("ratingAvg", 0.0),
                ratingCount = o.optInt("ratingCount")
            )
        }
    }

    suspend fun submitComment(
        session: BooxinAuthSession,
        pluginId: String,
        bodyText: String
    ): Result<PluginComment> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("body", bodyText.trim())
            val json = executeAuthorized(
                session,
                Request.Builder()
                    .url("$DEFAULT_ROOT/api/plugins/$pluginId/comments")
                    .post(body.toString().toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .build()
            )
            parseComment(JSONObject(json))
        }
    }

    suspend fun toggleCommentLike(
        session: BooxinAuthSession,
        pluginId: String,
        commentId: String
    ): Result<Pair<Boolean, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = executeAuthorized(
                session,
                Request.Builder()
                    .url("$DEFAULT_ROOT/api/plugins/$pluginId/comments/$commentId/like")
                    .post("{}".toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .build()
            )
            val o = JSONObject(json)
            o.optBoolean("liked") to o.optInt("likeCount")
        }
    }

    suspend fun reportComment(
        session: BooxinAuthSession,
        pluginId: String,
        commentId: String,
        reason: String? = null
    ): Result<PluginCommentReportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
            if (!reason.isNullOrBlank()) body.put("reason", reason.trim())
            val json = executeAuthorized(
                session,
                Request.Builder()
                    .url("$DEFAULT_ROOT/api/plugins/$pluginId/comments/$commentId/report")
                    .post(body.toString().toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .build()
            )
            val o = JSONObject(json)
            PluginCommentReportResult(
                reportId = o.optString("reportId"),
                aiReviewed = o.optBoolean("aiReviewed"),
                reportValid = if (o.isNull("reportValid")) null else o.optBoolean("reportValid"),
                category = o.optString("category"),
                actionLevel = o.optInt("actionLevel"),
                actionLevelName = o.optString("actionLevelName"),
                aiSummary = o.optString("aiSummary"),
                actionTaken = o.optString("actionTaken"),
                message = o.optString("message")
            )
        }
    }

    data class StorePluginRatingResult(
        val score: Int,
        val ratingAvg: Double,
        val ratingCount: Int
    )

    suspend fun submitApplication(
        session: BooxinAuthSession,
        name: String,
        downloadUrl: String,
        description: String,
        type: String = "other"
    ): Result<PluginApplication> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("name", name.trim())
                .put("downloadUrl", downloadUrl.trim())
                .put("description", description.trim())
                .put("type", type.trim().ifBlank { "other" })
            val json = executeAuthorized(
                session,
                Request.Builder()
                    .url("$DEFAULT_ROOT/api/applications")
                    .post(body.toString().toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .build()
            )
            parseApplication(JSONObject(json))
        }
    }

    suspend fun submitApplicationWithFile(
        session: BooxinAuthSession,
        name: String,
        description: String,
        type: String,
        file: File
    ): Result<PluginApplication> = withContext(Dispatchers.IO) {
        runCatching {
            if (file.length() > MAX_UPLOAD_BYTES) {
                error("OVERSIZE")
            }
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("name", name.trim())
                .addFormDataPart("description", description.trim())
                .addFormDataPart("type", type.trim().ifBlank { "other" })
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody(OCTET)
                )
                .build()
            val json = executeAuthorized(
                session,
                Request.Builder()
                    .url("$DEFAULT_ROOT/api/applications/upload")
                    .post(multipart)
                    .header("Accept", "application/json")
                    .build()
            )
            parseApplication(JSONObject(json))
        }
    }

    suspend fun myApplications(session: BooxinAuthSession): Result<List<PluginApplication>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = executeAuthorized(
                    session,
                    Request.Builder()
                        .url("$DEFAULT_ROOT/api/applications/mine")
                        .get()
                        .header("Accept", "application/json")
                        .build()
                )
                parseApplications(JSONObject(json).optJSONArray("items"))
            }
        }

    private fun parsePlugins(arr: JSONArray?): List<StorePlugin> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(parsePlugin(o))
            }
        }
    }

    private fun parsePlugin(o: JSONObject): StorePlugin =
        StorePlugin(
            id = o.optString("id"),
            name = o.optString("name"),
            description = o.optString("description"),
            downloadUrl = o.optString("downloadUrl"),
            type = o.optString("type", "other"),
            developerUserId = o.optString("developerUserId"),
            developerUsername = o.optString("developerUsername"),
            publishedAt = o.optString("publishedAt"),
            ratingAvg = o.optDouble("ratingAvg", 0.0),
            ratingCount = o.optInt("ratingCount"),
            commentCount = o.optInt("commentCount")
        )

    private fun parseComments(arr: JSONArray?): List<PluginComment> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(parseComment(o))
            }
        }
    }

    private fun parseComment(o: JSONObject): PluginComment =
        PluginComment(
            id = o.optString("id"),
            pluginId = o.optString("pluginId"),
            userId = o.optString("userId"),
            username = o.optString("username"),
            body = o.optString("body"),
            createdAt = o.optString("createdAt"),
            likeCount = o.optInt("likeCount"),
            likedByMe = o.optBoolean("likedByMe")
        )

    private fun parseApplications(arr: JSONArray?): List<PluginApplication> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(parseApplication(o))
            }
        }
    }

    private fun parseApplication(o: JSONObject): PluginApplication =
        PluginApplication(
            id = o.optString("id"),
            name = o.optString("name"),
            description = o.optString("description"),
            downloadUrl = o.optString("downloadUrl"),
            type = o.optString("type", "other"),
            status = o.optString("status"),
            rejectReason = o.optString("rejectReason"),
            createdAt = o.optString("createdAt"),
            updatedAt = o.optString("updatedAt"),
            reviewedAt = o.optString("reviewedAt").takeIf { it.isNotBlank() }
        )

    private fun executeGet(url: String): String =
        execute(Request.Builder().url(url).get().header("Accept", "application/json").build())

    private fun executeAuthorized(session: BooxinAuthSession, request: Request): String {
        val authed = request.newBuilder()
            .header("Authorization", "${session.tokenType} ${session.accessToken}")
            .build()
        return execute(authed)
    }

    private fun execute(request: Request): String {
        HttpClients.shared.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(body).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: body.ifBlank { "HTTP ${resp.code}" }
                throw IOException(msg)
            }
            return body
        }
    }
}
