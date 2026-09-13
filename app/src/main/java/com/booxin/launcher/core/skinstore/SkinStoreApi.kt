package com.booxin.launcher.core.skinstore

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

class SkinStoreApi {

    companion object {
        const val DEFAULT_ROOT = "https://boonix.art/skin-api"
        const val MAX_UPLOAD_BYTES = 2L * 1024L * 1024L
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val PNG = "image/png".toMediaType()
    }

    suspend fun listSkins(query: String? = null, model: String? = null): Result<List<StoreSkin>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val qs = buildList {
                    if (!query.isNullOrBlank()) add("q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}")
                    if (!model.isNullOrBlank()) add("model=${model.trim()}")
                }.joinToString("&")
                val url = if (qs.isBlank()) "$DEFAULT_ROOT/api/skins" else "$DEFAULT_ROOT/api/skins?$qs"
                parseSkins(JSONObject(executeGet(url)).optJSONArray("items"))
            }
        }

    suspend fun getSkin(id: String): Result<StoreSkin> = withContext(Dispatchers.IO) {
        runCatching { parseSkin(JSONObject(executeGet("$DEFAULT_ROOT/api/skins/$id"))) }
    }

    suspend fun markDownload(id: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val json = execute(
                Request.Builder()
                    .url("$DEFAULT_ROOT/api/skins/$id/download")
                    .post("{}".toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .build()
            )
            JSONObject(json).optString("textureUrl")
        }
    }

    suspend fun downloadTextureBytes(textureUrl: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                HttpClients.shared.newCall(
                    Request.Builder().url(textureUrl).get().build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) error("下载皮肤失败 HTTP ${resp.code}")
                    resp.body?.bytes() ?: error("空响应")
                }
            }
        }

    suspend fun listComments(
        skinId: String,
        limit: Int = 50,
        session: BooxinAuthSession? = null
    ): Result<List<SkinComment>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$DEFAULT_ROOT/api/skins/$skinId/comments?limit=$limit")
                .get()
                .header("Accept", "application/json")
                .build()
            val json = if (session != null) executeAuthorized(session, req) else execute(req)
            parseComments(JSONObject(json).optJSONArray("items"))
        }
    }

    suspend fun myRating(session: BooxinAuthSession, skinId: String): Result<Int?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = executeAuthorized(
                    session,
                    Request.Builder()
                        .url("$DEFAULT_ROOT/api/skins/$skinId/rating/mine")
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
        skinId: String,
        score: Int
    ): Result<SkinRatingResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("score", score)
            val json = executeAuthorized(
                session,
                Request.Builder()
                    .url("$DEFAULT_ROOT/api/skins/$skinId/rating")
                    .put(body.toString().toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .build()
            )
            val o = JSONObject(json)
            SkinRatingResult(
                score = o.optInt("score"),
                ratingAvg = o.optDouble("ratingAvg", 0.0),
                ratingCount = o.optInt("ratingCount")
            )
        }
    }

    suspend fun submitComment(
        session: BooxinAuthSession,
        skinId: String,
        bodyText: String
    ): Result<SkinComment> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("body", bodyText.trim())
            val json = executeAuthorized(
                session,
                Request.Builder()
                    .url("$DEFAULT_ROOT/api/skins/$skinId/comments")
                    .post(body.toString().toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .build()
            )
            parseComment(JSONObject(json))
        }
    }

    suspend fun toggleCommentLike(
        session: BooxinAuthSession,
        skinId: String,
        commentId: String
    ): Result<Pair<Boolean, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = executeAuthorized(
                session,
                Request.Builder()
                    .url("$DEFAULT_ROOT/api/skins/$skinId/comments/$commentId/like")
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
        skinId: String,
        commentId: String,
        reason: String? = null
    ): Result<SkinCommentReportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
            if (!reason.isNullOrBlank()) body.put("reason", reason.trim())
            val json = executeAuthorized(
                session,
                Request.Builder()
                    .url("$DEFAULT_ROOT/api/skins/$skinId/comments/$commentId/report")
                    .post(body.toString().toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .build()
            )
            val o = JSONObject(json)
            SkinCommentReportResult(
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

    suspend fun reportSkin(
        session: BooxinAuthSession,
        skinId: String,
        category: String,
        reason: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("category", category.trim())
            if (!reason.isNullOrBlank()) body.put("reason", reason.trim())
            val json = executeAuthorized(
                session,
                Request.Builder()
                    .url("$DEFAULT_ROOT/api/skins/$skinId/report")
                    .post(body.toString().toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .build()
            )
            JSONObject(json).optString("message", "已提交举报")
        }
    }

    suspend fun submitApplicationWithFile(
        session: BooxinAuthSession,
        name: String,
        description: String,
        model: String,
        file: File,
        originType: String,
        source: String = "",
        allowReprint: Boolean = true,
        tags: String = "",
        version: String = "1.0.0"
    ): Result<SkinApplication> = withContext(Dispatchers.IO) {
        runCatching {
            if (file.length() > MAX_UPLOAD_BYTES) error("OVERSIZE")
            val origin = when (originType.trim().lowercase()) {
                "reprint", "转载" -> "reprint"
                else -> "original"
            }
            val originTag = if (origin == "reprint") "转载" else "原创"
            val allowTag = if (allowReprint) "允许转载" else "禁止转载"
            val mergedTags = buildList {
                add(originTag)
                add(allowTag)
                tags.split(',', '，', ';', '；', ' ')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { add(it) }
            }.distinct().joinToString(",")
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("name", name.trim())
                .addFormDataPart("description", description.trim())
                .addFormDataPart("model", model.trim().ifBlank { "classic" })
                .addFormDataPart("tags", mergedTags)
                .addFormDataPart("version", version.trim().ifBlank { "1.0.0" })
                .addFormDataPart("originType", origin)
                .addFormDataPart("source", source.trim())
                .addFormDataPart("allowReprint", if (allowReprint) "true" else "false")
                .addFormDataPart("file", file.name, file.asRequestBody(PNG))
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

    suspend fun myApplications(session: BooxinAuthSession): Result<List<SkinApplication>> =
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

    private fun parseSkins(arr: JSONArray?): List<StoreSkin> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(parseSkin(o))
            }
        }
    }

    private fun parseSkin(o: JSONObject): StoreSkin {
        val tagsArr = o.optJSONArray("tags")
        val tags = buildList {
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    val t = tagsArr.optString(i).trim()
                    if (t.isNotEmpty()) add(t)
                }
            }
        }
        return StoreSkin(
            id = o.optString("id"),
            name = o.optString("name"),
            description = o.optString("description"),
            textureUrl = o.optString("textureUrl"),
            model = o.optString("model", "classic").ifBlank { "classic" },
            tags = tags,
            version = o.optString("version", "1.0.0").ifBlank { "1.0.0" },
            width = o.optInt("width", 64),
            height = o.optInt("height", 64),
            authorUserId = o.optString("authorUserId"),
            authorUsername = o.optString("authorUsername"),
            publishedAt = o.optString("publishedAt"),
            updatedAt = o.optString("updatedAt"),
            downloadCount = o.optInt("downloadCount"),
            ratingAvg = o.optDouble("ratingAvg", 0.0),
            ratingCount = o.optInt("ratingCount"),
            commentCount = o.optInt("commentCount"),
            originType = o.optString("originType").ifBlank {
                when {
                    tags.any { it.equals("转载", ignoreCase = true) } -> "reprint"
                    tags.any { it.equals("原创", ignoreCase = true) } -> "original"
                    else -> ""
                }
            },
            source = o.optString("source").ifBlank { o.optString("sourceUrl") },
            allowReprint = when {
                o.has("allowReprint") && !o.isNull("allowReprint") -> o.optBoolean("allowReprint")
                tags.any { it.contains("禁止转载") } -> false
                tags.any { it.contains("允许转载") } -> true
                else -> null
            }
        )
    }

    private fun parseComments(arr: JSONArray?): List<SkinComment> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(parseComment(o))
            }
        }
    }

    private fun parseComment(o: JSONObject): SkinComment =
        SkinComment(
            id = o.optString("id"),
            skinId = o.optString("skinId"),
            userId = o.optString("userId"),
            username = o.optString("username"),
            body = o.optString("body"),
            createdAt = o.optString("createdAt"),
            likeCount = o.optInt("likeCount"),
            likedByMe = o.optBoolean("likedByMe")
        )

    private fun parseApplications(arr: JSONArray?): List<SkinApplication> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(parseApplication(o))
            }
        }
    }

    private fun parseApplication(o: JSONObject): SkinApplication =
        SkinApplication(
            id = o.optString("id"),
            name = o.optString("name"),
            description = o.optString("description"),
            textureUrl = o.optString("textureUrl"),
            model = o.optString("model", "classic"),
            status = o.optString("status"),
            rejectReason = o.optString("rejectReason"),
            createdAt = o.optString("createdAt"),
            updatedAt = o.optString("updatedAt")
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
