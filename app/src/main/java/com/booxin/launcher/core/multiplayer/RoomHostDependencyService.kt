package com.booxin.launcher.core.multiplayer

import com.booxin.launcher.core.download.game.Digests
import com.booxin.launcher.core.net.FileDownloader
import com.booxin.launcher.core.net.HttpClients
import com.booxin.launcher.core.version.VersionModsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scan / publish / download room host mod dependencies — aligned with PC RoomHostDependencyService.
 */
class RoomHostDependencyService(
    private val downloader: FileDownloader = FileDownloader()
) {
    suspend fun scanInstance(
        versionId: String,
        pureGameVersion: String? = null,
        loaderHint: String? = null,
        onProgress: (String) -> Unit = {}
    ): RoomHostDependencyInfo = withContext(Dispatchers.IO) {
        val jsonFile = File(
            com.booxin.launcher.core.LauncherPaths.versionsDir,
            "$versionId/$versionId.json"
        )
        val root = runCatching {
            if (jsonFile.isFile) JSONObject(jsonFile.readText()) else null
        }.getOrNull()
        val gameVersion = pureGameVersion?.trim()?.takeIf { it.isNotEmpty() }
            ?: root?.let { LanServerPropertiesInstaller.resolveGameVersion(versionId, it) }
            ?: extractPureVersion(versionId)
        val loader = normalizeLoader(loaderHint)
            ?: root?.let { detectLoaderFromJson(it) }
            ?: detectLoaderFromVersionId(versionId)

        val jars = VersionModsManager.list(versionId)
            .filter { it.enabled }
            .map { it.file }
            .filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .take(MAX_MODS)

        if (jars.isEmpty()) {
            return@withContext RoomHostDependencyInfo(gameVersion = gameVersion, loader = loader)
        }

        val mods = ArrayList<RoomModDependency>(jars.size)
        jars.forEachIndexed { index, file ->
            onProgress("识别模组 ${index + 1}/${jars.size}：${file.name}")
            val resolved = resolveJar(file)
            mods += resolved ?: RoomModDependency(
                name = file.nameWithoutExtension,
                fileName = file.name,
                sha1 = Digests.sha1(file)
            )
        }
        RoomHostDependencyInfo(gameVersion = gameVersion, loader = loader, mods = mods)
    }

    suspend fun downloadModsToDirectory(
        mods: List<RoomModDependency>,
        modsDirectory: File,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> }
    ): List<File> = withContext(Dispatchers.IO) {
        modsDirectory.mkdirs()
        val downloadable = mods.filter { it.hasDownloadSource }
        val installed = ArrayList<File>()
        downloadable.forEachIndexed { index, mod ->
            val display = mod.fileName?.takeIf { it.isNotBlank() }
                ?: mod.name.takeIf { it.isNotBlank() }
                ?: "模组 ${index + 1}"
            onProgress(index, downloadable.size, "正在下载（${index + 1}/${downloadable.size}）：$display")
            val saved = downloadSingleMod(mod, modsDirectory)
            if (saved != null) {
                installed += saved
                onProgress(
                    index + 1,
                    downloadable.size,
                    "已完成（${index + 1}/${downloadable.size}）：${saved.name}"
                )
            } else {
                onProgress(
                    index + 1,
                    downloadable.size,
                    "跳过（${index + 1}/${downloadable.size}）：$display"
                )
            }
        }
        onProgress(downloadable.size, downloadable.size, "全部完成")
        installed
    }

    private suspend fun downloadSingleMod(mod: RoomModDependency, modsDirectory: File): File? {
        if (mod.source.equals("modrinth", ignoreCase = true)) {
            val fromVersion = mod.versionId?.takeIf { it.isNotBlank() }?.let { resolveModrinthVersionFile(it) }
            if (fromVersion != null) {
                return downloadUrlToMods(fromVersion.first, fromVersion.second, modsDirectory, mod.sha1)
            }
            val projectId = mod.projectId?.takeIf { it.isNotBlank() }
            if (projectId != null) {
                val latest = resolveLatestModrinthFile(projectId)
                if (latest != null) {
                    return downloadUrlToMods(latest.first, latest.second, modsDirectory, mod.sha1)
                }
            }
        }

        val url = mod.downloadUrl?.trim().orEmpty()
        if (url.isEmpty()) return null
        val fileName = mod.fileName?.takeIf { it.isNotBlank() }
            ?: VersionModsManager.fileNameFromUrl(url)
        return downloadUrlToMods(url, fileName, modsDirectory, mod.sha1)
    }

    private suspend fun downloadUrlToMods(
        url: String,
        fileName: String,
        modsDirectory: File,
        expectedSha1: String?
    ): File? {
        val safeName = sanitizeFileName(fileName).ifBlank { "mod.jar" }
        val dest = File(modsDirectory, safeName)
        if (dest.isFile && dest.length() > 0L) {
            if (expectedSha1.isNullOrBlank() || Digests.matchesSha1(dest, expectedSha1)) {
                return dest
            }
        }
        val result = downloader.download(
            urls = modrinthDownloadCandidates(url),
            destination = dest,
            accelerate = true
        )
        val file = result.getOrNull() ?: return null
        if (!expectedSha1.isNullOrBlank() && !Digests.matchesSha1(file, expectedSha1)) {
            file.delete()
            return null
        }
        return file
    }

    private suspend fun resolveModrinthVersionFile(versionId: String): Pair<String, String>? {
        val text = downloader.downloadText(
            modrinthApiCandidates("/v2/version/$versionId")
        ).getOrNull() ?: return null
        return parsePrimaryFile(JSONObject(text))
    }

    private suspend fun resolveLatestModrinthFile(projectId: String): Pair<String, String>? {
        val text = downloader.downloadText(
            modrinthApiCandidates("/v2/project/$projectId/version")
        ).getOrNull() ?: return null
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            parsePrimaryFile(item)?.let { return it }
        }
        return null
    }

    private fun parsePrimaryFile(version: JSONObject): Pair<String, String>? {
        val files = version.optJSONArray("files") ?: return null
        var fallback: Pair<String, String>? = null
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val url = file.optString("url").trim()
            val name = file.optString("filename").trim().ifBlank { "mod.jar" }
            if (url.isEmpty()) continue
            val pair = url to name
            if (file.optBoolean("primary", false)) return pair
            if (fallback == null) fallback = pair
        }
        return fallback
    }

    private suspend fun resolveJar(file: File): RoomModDependency? {
        val sha1 = Digests.sha1(file)
        resolveModrinthByHash(sha1, file.name)?.let { return it }
        resolveCurseForgeByFingerprint(file, sha1)?.let { return it }
        return null
    }

    private suspend fun resolveModrinthByHash(sha1: String, fileName: String): RoomModDependency? {
        val text = downloader.downloadText(
            modrinthApiCandidates("/v2/version_file/$sha1?algorithm=sha1")
        ).getOrNull() ?: return null
        val version = JSONObject(text)
        val projectId = version.optString("project_id").trim()
        if (projectId.isEmpty()) return null
        val primary = parsePrimaryFile(version)
        var slug: String? = null
        runCatching {
            val projectText = downloader.downloadText(
                modrinthApiCandidates("/v2/project/$projectId")
            ).getOrThrow()
            slug = JSONObject(projectText).optString("slug").ifBlank { null }
        }
        val projectKey = slug?.takeIf { it.isNotBlank() } ?: projectId
        return RoomModDependency(
            name = version.optString("name").ifBlank { File(fileName).nameWithoutExtension },
            source = "modrinth",
            projectId = projectId,
            versionId = version.optString("id").ifBlank { null },
            pageUrl = "https://modrinth.com/mod/$projectKey",
            downloadUrl = primary?.first,
            fileName = primary?.second ?: fileName,
            sha1 = sha1
        )
    }

    private suspend fun resolveCurseForgeByFingerprint(
        file: File,
        sha1: String
    ): RoomModDependency? = withContext(Dispatchers.IO) {
        val fingerprint = computeCurseForgeFingerprint(file)
        val body = JSONArray().put(fingerprint).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val urls = listOf(
            "https://mod.mcimirror.top/curseforge/v1/fingerprints",
            "https://api.curseforge.com/v1/fingerprints"
        )
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .header("User-Agent", HttpClients.USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                HttpClients.shared.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val json = response.body?.string().orEmpty()
                    val root = JSONObject(json)
                    val exact = root.optJSONObject("data")?.optJSONArray("exactMatches")
                        ?: root.optJSONArray("exactMatches")
                    val match = exact?.optJSONObject(0) ?: return@use
                    val fileObj = match.optJSONObject("file") ?: match
                    val fileId = fileObj.optInt("id", 0)
                    val modId = fileObj.optInt("modId", match.optInt("modId", 0))
                    if (modId <= 0) return@use
                    val fileName = fileObj.optString("fileName").ifBlank { file.name }
                    val displayName = fileObj.optString("displayName").ifBlank { fileName }
                    val downloadUrl = fileObj.optString("downloadUrl").ifBlank { null }
                    return@withContext RoomModDependency(
                        name = displayName,
                        source = "curseforge",
                        projectId = modId.toString(),
                        versionId = fileId.takeIf { it > 0 }?.toString(),
                        pageUrl = "https://www.curseforge.com/minecraft/mc-mods/$modId",
                        downloadUrl = downloadUrl,
                        fileName = fileName,
                        sha1 = sha1
                    )
                }
            } catch (_: Throwable) {
                // try next mirror
            }
        }
        null
    }

    companion object {
        const val MAX_MODS = 128
        const val MAX_MODS_JSON_LENGTH = 96_000

        fun serializeMods(mods: List<RoomModDependency>?): String {
            if (mods.isNullOrEmpty()) return "[]"
            val arr = JSONArray()
            mods.asSequence()
                .filter { it.hasDownloadSource }
                .take(MAX_MODS)
                .forEach { mod ->
                    arr.put(
                        JSONObject()
                            .put("Name", mod.name)
                            .put("Source", mod.source)
                            .put("ProjectId", mod.projectId)
                            .put("VersionId", mod.versionId)
                            .put("PageUrl", mod.pageUrl)
                            .put("DownloadUrl", mod.downloadUrl)
                            .put("FileName", mod.fileName)
                            .put("Sha1", mod.sha1)
                    )
                }
            val json = arr.toString()
            return if (json.length <= MAX_MODS_JSON_LENGTH) json else json.take(MAX_MODS_JSON_LENGTH)
        }

        fun deserializeMods(modsJson: String?): List<RoomModDependency> {
            if (modsJson.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(modsJson.trim())
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val mod = parseModObject(o)
                        if (mod.hasDownloadSource) add(mod)
                        if (size >= MAX_MODS) break
                    }
                }
            }.getOrDefault(emptyList())
        }

        fun parseModObject(o: JSONObject): RoomModDependency {
            fun s(vararg keys: String): String? {
                for (k in keys) {
                    val v = o.optString(k).trim()
                    if (v.isNotEmpty() && v != "null") return v
                }
                return null
            }
            return RoomModDependency(
                name = s("Name", "name").orEmpty(),
                source = s("Source", "source").orEmpty(),
                projectId = s("ProjectId", "projectId"),
                versionId = s("VersionId", "versionId"),
                pageUrl = s("PageUrl", "pageUrl"),
                downloadUrl = s("DownloadUrl", "downloadUrl"),
                fileName = s("FileName", "fileName"),
                sha1 = s("Sha1", "sha1")
            )
        }

        fun extractPureVersion(versionId: String?): String {
            if (versionId.isNullOrBlank()) return ""
            var trimmed = versionId.trim()
            val underscore = trimmed.indexOf('_')
            if (underscore > 0) trimmed = trimmed.substring(0, underscore)
            val loaders = listOf(
                "-fabric-", "-forge-", "-neoforge-", "-quilt-",
                "-fabric", "-forge", "-neoforge", "-quilt"
            )
            for (marker in loaders) {
                val idx = trimmed.indexOf(marker, ignoreCase = true)
                if (idx > 0) return trimmed.substring(0, idx)
            }
            return trimmed
        }

        fun detectLoaderFromVersionId(versionId: String?): String? {
            if (versionId.isNullOrBlank()) return null
            val lower = versionId.lowercase()
            return when {
                "neoforge" in lower -> "neoforge"
                "forge" in lower -> "forge"
                "fabric" in lower -> "fabric"
                "quilt" in lower -> "quilt"
                else -> null
            }
        }

        fun detectLoaderFromJson(root: JSONObject): String? {
            val main = root.optString("mainClass").lowercase()
            when {
                "fabric" in main -> return "fabric"
                "quilt" in main -> return "quilt"
                "neoforge" in main -> return "neoforge"
                "cpw.mods" in main || "forge" in main -> return "forge"
            }
            val libs = root.optJSONArray("libraries") ?: return null
            for (i in 0 until libs.length()) {
                val name = libs.optJSONObject(i)?.optString("name").orEmpty().lowercase()
                when {
                    name.startsWith("net.fabricmc:fabric-loader") -> return "fabric"
                    name.startsWith("org.quiltmc:quilt-loader") -> return "quilt"
                    name.startsWith("net.neoforged:") -> return "neoforge"
                    name.startsWith("net.minecraftforge:forge") -> return "forge"
                }
            }
            return null
        }

        fun normalizeLoader(loader: String?): String? {
            if (loader.isNullOrBlank()) return null
            val trimmed = loader.trim().lowercase()
            return when (trimmed) {
                "fabric", "forge", "neoforge", "quilt", "vanilla" -> trimmed
                else -> trimmed.take(32)
            }
        }

        fun sanitizeFileName(name: String): String {
            val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            return cleaned.ifBlank { "mod" }
        }

        /**
         * CurseForge fingerprint: MurmurHash2 of file bytes, skipping whitespace 0x09/0x0a/0x0d/0x20.
         */
        fun computeCurseForgeFingerprint(file: File): Long {
            val raw = file.readBytes()
            val filtered = ByteArray(raw.count { b ->
                b.toInt() and 0xFF !in intArrayOf(0x09, 0x0a, 0x0d, 0x20)
            })
            var index = 0
            for (b in raw) {
                val v = b.toInt() and 0xFF
                if (v !in intArrayOf(0x09, 0x0a, 0x0d, 0x20)) {
                    filtered[index++] = b
                }
            }
            return murmurHash2(filtered, 1u).toLong() and 0xFFFFFFFFL
        }

        private fun murmurHash2(data: ByteArray, seed: UInt): UInt {
            val m = 0x5bd1e995u
            val r = 24
            var length = data.size.toUInt()
            var h = seed xor length
            var offset = 0
            while (length >= 4u) {
                var k = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
                k *= m
                k = k xor (k shr r)
                k *= m
                h *= m
                h = h xor k
                offset += 4
                length -= 4u
            }
            when (length.toInt()) {
                3 -> {
                    h = h xor (data[offset + 2].toUInt() and 0xFFu shl 16)
                    h = h xor (data[offset + 1].toUInt() and 0xFFu shl 8)
                    h = h xor (data[offset].toUInt() and 0xFFu)
                    h *= m
                }
                2 -> {
                    h = h xor (data[offset + 1].toUInt() and 0xFFu shl 8)
                    h = h xor (data[offset].toUInt() and 0xFFu)
                    h *= m
                }
                1 -> {
                    h = h xor (data[offset].toUInt() and 0xFFu)
                    h *= m
                }
            }
            h = h xor (h shr 13)
            h *= m
            h = h xor (h shr 15)
            return h
        }

        private fun modrinthApiCandidates(path: String): List<String> =
            com.booxin.launcher.core.community.ModrinthUrlCandidates.api(path)

        private fun modrinthDownloadCandidates(rawUrl: String): List<String> =
            com.booxin.launcher.core.community.ModrinthUrlCandidates.fileDownloads(rawUrl)
    }
}
