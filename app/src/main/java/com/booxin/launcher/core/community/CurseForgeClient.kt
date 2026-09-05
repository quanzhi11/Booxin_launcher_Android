package com.booxin.launcher.core.community

import com.booxin.launcher.core.net.FileDownloader
import com.booxin.launcher.data.model.CommunityContentType
import com.booxin.launcher.data.model.CommunityLoader
import com.booxin.launcher.data.model.ModrinthDependency
import com.booxin.launcher.data.model.ModrinthDependencyType
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.data.model.ModrinthProjectVersion
import com.booxin.launcher.data.model.ModrinthSearchPage
import com.booxin.launcher.data.model.ModrinthVersionFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * CurseForge (CFCore) client via MCIM mirror + BMCL download fallbacks.
 * Results are mapped into the existing Modrinth* models for shared UI.
 */
class CurseForgeClient(
    private val downloader: FileDownloader = FileDownloader()
) {
    suspend fun searchProjects(
        query: String,
        contentType: CommunityContentType,
        gameVersion: String?,
        loader: CommunityLoader,
        offset: Int = 0,
        limit: Int = PAGE_SIZE
    ): Result<ModrinthSearchPage> = withContext(Dispatchers.IO) {
        runCatching {
            val classId = classIdFor(contentType)
            val url = requireNotNull("$API_MIRROR/v1/mods/search".toHttpUrlOrNull())
                .newBuilder()
                .addQueryParameter("gameId", GAME_ID.toString())
                .addQueryParameter("classId", classId.toString())
                .addQueryParameter("searchFilter", query)
                .addQueryParameter("sortField", "2") // Popularity
                .addQueryParameter("sortOrder", "desc")
                .addQueryParameter("index", offset.coerceAtLeast(0).toString())
                .addQueryParameter("pageSize", limit.coerceIn(1, 50).toString())
                .apply {
                    if (!gameVersion.isNullOrBlank()) {
                        addQueryParameter("gameVersion", gameVersion)
                    }
                    loaderToModLoaderType(loader)?.let {
                        addQueryParameter("modLoaderType", it.toString())
                    }
                }
                .build()
                .toString()
            val root = JSONObject(downloadApiUrl(url))
            val data = root.optJSONArray("data") ?: JSONArray()
            val pagination = root.optJSONObject("pagination")
            val projects = buildList {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    add(parseMod(item))
                }
            }
            ModrinthSearchPage(
                projects = projects,
                offset = offset,
                limit = limit,
                totalHits = pagination?.optInt("totalCount", projects.size) ?: projects.size
            )
        }
    }

    suspend fun getProject(modId: String): Result<ModrinthProject> = withContext(Dispatchers.IO) {
        runCatching {
            val id = normalizeModId(modId)
            val root = JSONObject(downloadApiUrl("$API_MIRROR/v1/mods/$id"))
            val data = root.optJSONObject("data") ?: error("CurseForge 项目不存在: $id")
            parseMod(data)
        }
    }

    suspend fun getProjectFiles(
        modId: String,
        gameVersion: String? = null,
        loader: CommunityLoader = CommunityLoader.ANY,
        pageSize: Int = 50
    ): Result<List<ModrinthProjectVersion>> = withContext(Dispatchers.IO) {
        runCatching {
            val id = normalizeModId(modId)
            val collected = ArrayList<ModrinthProjectVersion>()
            var index = 0
            repeat(4) {
                if (collected.size >= 120) return@repeat
                val url = requireNotNull("$API_MIRROR/v1/mods/$id/files".toHttpUrlOrNull())
                    .newBuilder()
                    .addQueryParameter("index", index.toString())
                    .addQueryParameter("pageSize", pageSize.coerceIn(1, 50).toString())
                    .apply {
                        if (!gameVersion.isNullOrBlank()) {
                            addQueryParameter("gameVersion", gameVersion)
                        }
                        loaderToModLoaderType(loader)?.let {
                            addQueryParameter("modLoaderType", it.toString())
                        }
                    }
                    .build()
                    .toString()
                val root = JSONObject(downloadApiUrl(url))
                val data = root.optJSONArray("data") ?: JSONArray()
                if (data.length() == 0) return@repeat
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    parseFile(item, projectId = id)?.let(collected::add)
                }
                val pagination = root.optJSONObject("pagination")
                val total = pagination?.optInt("totalCount", 0) ?: 0
                index += data.length()
                if (index >= total) return@repeat
            }
            collected.sortedByDescending { it.datePublished.orEmpty() }
        }
    }

    private fun parseMod(item: JSONObject): ModrinthProject {
        val id = item.optInt("id", 0).toString()
        val authors = item.optJSONArray("authors")
        val author = if (authors != null && authors.length() > 0) {
            authors.optJSONObject(0)?.optString("name").orEmpty()
        } else {
            ""
        }
        val logo = item.optJSONObject("logo")
        val icon = logo?.optString("thumbnailUrl")?.ifBlank { null }
            ?: logo?.optString("url")?.ifBlank { null }
        val categories = buildList {
            val cats = item.optJSONArray("categories") ?: return@buildList
            for (i in 0 until cats.length()) {
                val name = cats.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (name.isNotEmpty()) add(name.lowercase())
            }
        }
        val latest = item.optJSONArray("latestFilesIndexes")
        val gameVersions = linkedSetOf<String>()
        val loaders = linkedSetOf<String>()
        if (latest != null) {
            for (i in 0 until latest.length()) {
                val idx = latest.optJSONObject(i) ?: continue
                idx.optString("gameVersion").takeIf { it.isNotBlank() }?.let(gameVersions::add)
                modLoaderTypeToName(idx.optInt("modLoader", -1))?.let(loaders::add)
            }
        }
        return ModrinthProject(
            id = idPrefix(id),
            slug = item.optString("slug").ifBlank { id },
            title = item.optString("name").ifBlank { item.optString("slug") },
            description = item.optString("summary"),
            author = author,
            iconUrl = icon,
            downloads = item.optInt("downloadCount", 0),
            categories = categories,
            gameVersions = gameVersions.toList(),
            loaders = loaders.toList(),
            body = item.optString("summary").ifBlank { null }
        )
    }

    private fun parseFile(item: JSONObject, projectId: String): ModrinthProjectVersion? {
        val fileId = item.optInt("id", 0)
        if (fileId <= 0) return null
        val fileName = item.optString("fileName").ifBlank {
            item.optString("displayName")
        }
        if (fileName.isBlank()) return null
        val downloadUrl = resolveDownloadUrl(
            modId = projectId,
            fileId = fileId,
            apiUrl = item.optString("downloadUrl")
        )
        val sha1 = sha1FromHashes(item.optJSONArray("hashes"))
        val gameVersions = ArrayList<String>()
        val loaders = linkedSetOf<String>()
        val gv = item.optJSONArray("gameVersions")
        if (gv != null) {
            for (i in 0 until gv.length()) {
                val v = gv.optString(i).trim()
                if (v.isEmpty()) continue
                when (v.lowercase()) {
                    "forge" -> loaders += "forge"
                    "neoforge" -> loaders += "neoforge"
                    "fabric" -> loaders += "fabric"
                    "quilt" -> loaders += "quilt"
                    "client", "server" -> Unit
                    else -> if (v.firstOrNull()?.isDigit() == true) gameVersions += v
                }
            }
        }
        val deps = parseDependencies(item.optJSONArray("dependencies"))
        val releaseType = when (item.optInt("releaseType", 1)) {
            1 -> "release"
            2 -> "beta"
            3 -> "alpha"
            else -> "release"
        }
        return ModrinthProjectVersion(
            id = idPrefix("$projectId:$fileId"),
            name = item.optString("displayName").ifBlank { fileName },
            versionNumber = fileName.removeSuffix(".jar").removeSuffix(".zip"),
            changelog = null,
            datePublished = item.optString("fileDate").ifBlank { null },
            versionType = releaseType,
            gameVersions = gameVersions.distinct(),
            loaders = loaders.toList(),
            files = listOf(
                ModrinthVersionFile(
                    url = downloadUrl,
                    filename = fileName,
                    primary = true,
                    size = item.optLong("fileLength", 0L),
                    sha1 = sha1
                )
            ),
            dependencies = deps,
            projectId = idPrefix(projectId)
        )
    }

    private fun parseDependencies(arr: JSONArray?): List<ModrinthDependency> {
        if (arr == null || arr.length() == 0) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val dep = arr.optJSONObject(i) ?: continue
                val modId = dep.optInt("modId", 0)
                if (modId <= 0) continue
                // 1=embeddedLibrary 2=optionalDependency 3=requiredDependency
                // 4=tool 5=incompatible 6=include
                val type = when (dep.optInt("relationType", 0)) {
                    3 -> ModrinthDependencyType.REQUIRED
                    2 -> ModrinthDependencyType.OPTIONAL
                    5 -> ModrinthDependencyType.INCOMPATIBLE
                    1, 6 -> ModrinthDependencyType.EMBEDDED
                    else -> ModrinthDependencyType.UNKNOWN
                }
                add(
                    ModrinthDependency(
                        projectId = idPrefix(modId.toString()),
                        versionId = null,
                        fileName = null,
                        type = type
                    )
                )
            }
        }
    }

    private fun sha1FromHashes(arr: JSONArray?): String? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            // algo 1 = SHA1
            if (h.optInt("algo", 0) == 1) {
                return h.optString("value").ifBlank { null }
            }
        }
        return null
    }

    private fun resolveDownloadUrl(modId: String, fileId: Int, apiUrl: String): String {
        val fromApi = apiUrl.trim()
        if (fromApi.startsWith("http")) return fromApi
        // CF often omits downloadUrl for third-party clients — BMCL / MCIM still work.
        return "https://bmclapi2.bangbang93.com/curseforge/download/$modId/$fileId"
    }

    private suspend fun downloadApiUrl(url: String): String {
        val candidates = apiCandidates(url)
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val result = downloader.downloadText(candidate)
            if (result.isSuccess) return result.getOrThrow()
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: IOException("CurseForge 请求失败: $url")
    }

    private fun apiCandidates(url: String): List<String> {
        val path = when {
            url.startsWith(API_MIRROR) -> url.removePrefix(API_MIRROR)
            url.startsWith(API_OFFICIAL) -> url.removePrefix(API_OFFICIAL)
            else -> return listOf(url)
        }
        val normalized = if (path.startsWith("/")) path else "/$path"
        val mirror = "$API_MIRROR$normalized"
        val official = "$API_OFFICIAL$normalized"
        return listOf(mirror) // Official CFCore needs API key; MCIM is the public path.
    }

    companion object {
        const val PAGE_SIZE = 20
        private const val GAME_ID = 432
        private const val API_MIRROR = "https://mod.mcimirror.top/curseforge"
        private const val API_OFFICIAL = "https://api.curseforge.com"

        const val ID_PREFIX = "cf:"

        fun idPrefix(rawId: String): String {
            val clean = rawId.removePrefix(ID_PREFIX).trim()
            return "$ID_PREFIX$clean"
        }

        fun normalizeModId(rawId: String): String =
            rawId.removePrefix(ID_PREFIX).substringBefore(':').trim()

        fun isCurseForgeId(rawId: String): Boolean =
            rawId.startsWith(ID_PREFIX, ignoreCase = true) ||
                rawId.all { it.isDigit() }

        fun classIdFor(type: CommunityContentType): Int = when (type) {
            CommunityContentType.MOD -> 6
            CommunityContentType.RESOURCE_PACK -> 12
            CommunityContentType.MODPACK -> 4471
            CommunityContentType.SHADER -> 6552
        }

        fun loaderToModLoaderType(loader: CommunityLoader): Int? = when (loader) {
            CommunityLoader.ANY -> null
            CommunityLoader.FORGE -> 1
            CommunityLoader.FABRIC -> 4
            CommunityLoader.QUILT -> 5
            CommunityLoader.NEOFORGE -> 6
        }

        fun modLoaderTypeToName(type: Int): String? = when (type) {
            1 -> "forge"
            4 -> "fabric"
            5 -> "quilt"
            6 -> "neoforge"
            else -> null
        }

        /** File CDN candidates for a CurseForge download URL / BMCL link. */
        fun fileDownloads(rawUrl: String, modId: String? = null, fileId: Int? = null): List<String> {
            val input = rawUrl.trim()
            if (input.isEmpty() && (modId == null || fileId == null)) return emptyList()
            val urls = linkedSetOf<String>()
            if (input.isNotEmpty()) {
                urls += input
                rewriteForgeCdn(input)?.let { urls += it }
            }
            if (!modId.isNullOrBlank() && fileId != null && fileId > 0) {
                val clean = normalizeModId(modId)
                urls += "https://bmclapi2.bangbang93.com/curseforge/download/$clean/$fileId"
                urls += "https://mod.mcimirror.top/files/${fileId / 1000}/${fileId % 1000}"
            }
            return urls.toList().sortedBy { u ->
                when {
                    "forgecdn" in u || "curseforge.com" in u -> 0
                    "bmclapi" in u -> 1
                    else -> 2
                }
            }
        }

        private fun rewriteForgeCdn(url: String): String? {
            val prefixes = listOf(
                "https://edge.forgecdn.net/",
                "http://edge.forgecdn.net/",
                "https://mediafilez.forgecdn.net/",
                "http://mediafilez.forgecdn.net/",
                "https://media.forgecdn.net/",
                "http://media.forgecdn.net/"
            )
            for (prefix in prefixes) {
                if (url.startsWith(prefix, ignoreCase = true)) {
                    return "https://mod.mcimirror.top/" + url.substring(prefix.length)
                }
            }
            return null
        }
    }
}
