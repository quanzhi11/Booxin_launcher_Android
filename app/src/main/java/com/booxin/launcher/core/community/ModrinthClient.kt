package com.booxin.launcher.core.community

import com.booxin.launcher.core.net.FileDownloader
import com.booxin.launcher.data.model.CommunityContentType
import com.booxin.launcher.data.model.CommunityLoader
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.data.model.ModrinthProjectVersion
import com.booxin.launcher.data.model.ModrinthVersionFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

class ModrinthClient(
    private val downloader: FileDownloader = FileDownloader()
) {
    suspend fun searchProjects(
        query: String,
        contentType: CommunityContentType,
        gameVersion: String?,
        loader: CommunityLoader,
        offset: Int = 0,
        limit: Int = 20
    ): Result<List<ModrinthProject>> = withContext(Dispatchers.IO) {
        runCatching {
            val facets = buildFacets(contentType, gameVersion, loader)
            val url = requireNotNull("$apiRoot/v2/search".toHttpUrlOrNull())
                .newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("facets", facets.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("limit", limit.toString())
                // Match FCL behavior: Modrinth side tends to use relevance/name-style sorting.
                .addQueryParameter("index", "relevance")
                .build()
                .toString()
            val root = JSONObject(downloader.downloadText(url).getOrThrow())
            val hits = root.optJSONArray("hits") ?: JSONArray()
            buildList {
                for (i in 0 until hits.length()) {
                    val item = hits.optJSONObject(i) ?: continue
                    add(
                        ModrinthProject(
                            id = item.optString("project_id").ifBlank { item.optString("slug") },
                            slug = item.optString("slug").ifBlank { item.optString("project_id") },
                            title = item.optString("title").ifBlank { item.optString("slug") },
                            description = item.optString("description"),
                            author = item.optString("author"),
                            iconUrl = item.optString("icon_url").ifBlank { null },
                            downloads = item.optInt("downloads", 0),
                            categories = item.optJSONArray("categories").toStringList(),
                            gameVersions = item.optJSONArray("versions").toStringList(),
                            loaders = item.optJSONArray("display_categories").toStringList()
                        )
                    )
                }
            }
        }
    }

    suspend fun getProjectVersions(projectId: String): Result<List<ModrinthProjectVersion>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$apiRoot/v2/project/$projectId/version"
                val arr = JSONArray(downloader.downloadText(url).getOrThrow())
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val files = item.optJSONArray("files").toFiles()
                        if (files.isEmpty()) continue
                        add(
                            ModrinthProjectVersion(
                                id = item.optString("id"),
                                name = item.optString("name").ifBlank {
                                    item.optString("version_number")
                                },
                                versionNumber = item.optString("version_number"),
                                changelog = item.optString("changelog").ifBlank { null },
                                datePublished = item.optString("date_published").ifBlank { null },
                                versionType = item.optString("version_type").ifBlank { "release" },
                                gameVersions = item.optJSONArray("game_versions").toStringList(),
                                loaders = item.optJSONArray("loaders").toStringList(),
                                files = files
                            )
                        )
                    }
                }.sortedByDescending { it.datePublished.orEmpty() }
            }
        }

    private fun buildFacets(
        contentType: CommunityContentType,
        gameVersion: String?,
        loader: CommunityLoader
    ): JSONArray {
        val outer = JSONArray()
        outer.put(JSONArray().put("project_type:${contentType.projectType}"))
        if (!gameVersion.isNullOrBlank()) {
            outer.put(JSONArray().put("versions:$gameVersion"))
        }
        if (contentType == CommunityContentType.MOD && loader != CommunityLoader.ANY) {
            // Modrinth search lumps loaders into categories, which is what FCL also uses.
            outer.put(JSONArray().put("categories:${loader.apiValue}"))
        }
        return outer
    }

    private fun JSONArray?.toStringList(): List<String> = buildList {
        if (this@toStringList == null) return@buildList
        for (i in 0 until this@toStringList.length()) {
            val value = this@toStringList.optString(i).trim()
            if (value.isNotEmpty()) add(value)
        }
    }

    private fun JSONArray?.toFiles(): List<ModrinthVersionFile> = buildList {
        if (this@toFiles == null) return@buildList
        for (i in 0 until this@toFiles.length()) {
            val file = this@toFiles.optJSONObject(i) ?: continue
            val hashes = file.optJSONObject("hashes")
            add(
                ModrinthVersionFile(
                    url = file.optString("url"),
                    filename = file.optString("filename"),
                    primary = file.optBoolean("primary", false),
                    size = file.optLong("size", 0L),
                    sha1 = hashes?.optString("sha1")?.ifBlank { null }
                )
            )
        }
    }

    companion object {
        private const val apiRoot = "https://api.modrinth.com"
    }
}
