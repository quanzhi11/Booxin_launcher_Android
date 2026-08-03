package com.booxin.launcher.core.multiplayer

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.java.MinecraftJavaRequirement
import com.booxin.launcher.core.net.HttpClients
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Auto-installs [mcwifipnp](https://modrinth.com/mod/mcwifipnp) (LAN World Plug-n-Play)
 * so hosts can disable online-mode when opening to LAN — required for offline accounts.
 *
 * Also writes per-world `mcwifipnp.json` with OnlineMode=false by default.
 */
object LanServerPropertiesInstaller {

    private const val PROJECT = "mcwifipnp"
    private const val API = "https://api.modrinth.com/v2/project/$PROJECT/version"

    data class Result(
        val installed: Boolean,
        val message: String,
        val jarName: String? = null
    )

    fun ensureInstalled(versionId: String): Result {
        val versionRoot = File(LauncherPaths.versionsDir, versionId)
        val jsonFile = File(versionRoot, "$versionId.json")
        if (!jsonFile.isFile) {
            return Result(false, "缺少 version.json，跳过联机模组")
        }
        val root = runCatching { JSONObject(jsonFile.readText()) }.getOrNull()
            ?: return Result(false, "version.json 解析失败")

        val loader = detectLoader(root) ?: run {
            writeOfflineDefaults(versionRoot)
            return Result(false, "非 Fabric/Forge 版本，无法自动安装联机模组（原版需手装）")
        }

        val modsDir = File(versionRoot, "mods").also { it.mkdirs() }
        if (alreadyInstalled(modsDir)) {
            writeOfflineDefaults(versionRoot)
            return Result(true, "联机模组已存在", existingJarName(modsDir))
        }

        val gameVersion = resolveGameVersion(versionId, root)
        val file = resolveDownload(gameVersion, loader)
            ?: return Result(
                false,
                "未找到适合 $gameVersion ($loader) 的 mcwifipnp（含邻近版本回退）"
            )

        val dest = File(modsDir, file.filename)
        download(file.url, dest)
        writeOfflineDefaults(versionRoot)
        return Result(true, "已安装联机模组 ${file.filename}（默认关闭正版验证）", file.filename)
    }

    /** Force OnlineMode=false in every world under saves/. */
    fun writeOfflineDefaults(versionRoot: File) {
        val saves = File(versionRoot, "saves")
        if (!saves.isDirectory) return
        saves.listFiles()?.filter { it.isDirectory }?.forEach { world ->
            patchWorldConfig(File(world, "mcwifipnp.json"))
        }
    }

    private fun patchWorldConfig(file: File) {
        val obj = if (file.isFile) {
            runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        // Cover both legacy PascalCase (1.16) and kebab-case (modern) keys.
        obj.put("OnlineMode", false)
        obj.put("online-mode", false)
        if (!obj.has("port")) obj.put("port", 25565)
        if (!obj.has("maxPlayers") && !obj.has("max-players")) obj.put("maxPlayers", 8)
        if (!obj.has("GameMode") && !obj.has("gamemode")) obj.put("GameMode", "survival")
        if (!obj.has("motd")) obj.put("motd", "Booxin LAN")
        if (!obj.has("UseUPnP") && !obj.has("enable-upnp")) obj.put("UseUPnP", false)
        if (!obj.has("AllowCommands") && !obj.has("allow-host-cheat")) {
            obj.put("AllowCommands", true)
        }
        if (!obj.has("EnablePvP") && !obj.has("pvp")) obj.put("EnablePvP", true)
        if (!obj.has("CopyToClipboard") && !obj.has("get-public-ip")) {
            obj.put("CopyToClipboard", false)
        }
        if (!obj.has("EnableUUIDFixer") && !obj.has("enable-uuid-fixer")) {
            obj.put("EnableUUIDFixer", false)
        }
        file.parentFile?.mkdirs()
        file.writeText(obj.toString(2) + "\n")
    }

    private fun alreadyInstalled(modsDir: File): Boolean =
        modsDir.listFiles()?.any { isLanModJar(it.name) } == true

    private fun existingJarName(modsDir: File): String? =
        modsDir.listFiles()?.firstOrNull { isLanModJar(it.name) }?.name

    private fun isLanModJar(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jar") && (
            n.contains("mcwifipnp") ||
                n.contains("lan-server-properties") ||
                n.contains("lanserverproperties")
            )
    }

    private fun detectLoader(root: JSONObject): String? {
        val main = root.optString("mainClass").lowercase()
        when {
            "fabric" in main -> return "fabric"
            "neoforge" in main || "cpw.mods" in main && "neoforge" in root.toString().lowercase() ->
                return "neoforge"
            "forge" in main || "cpw.mods" in main -> return "forge"
        }
        val libs = root.optJSONArray("libraries") ?: return null
        for (i in 0 until libs.length()) {
            val name = libs.optJSONObject(i)?.optString("name").orEmpty().lowercase()
            when {
                name.startsWith("net.fabricmc:fabric-loader") -> return "fabric"
                name.startsWith("net.neoforged:") -> return "neoforge"
                name.startsWith("net.minecraftforge:forge") -> return "forge"
            }
        }
        return null
    }

    /**
     * Prefer inheritsFrom (Fabric/Forge wrappers), else strip loader suffixes from id.
     */
    fun resolveGameVersion(versionId: String, root: JSONObject): String {
        val inherits = root.optString("inheritsFrom").ifBlank { null }
        if (inherits != null) {
            MinecraftJavaRequirement.parseVersion(inherits)?.let {
                return formatParsed(it)
            }
            return inherits.substringBefore('-').substringBefore('_')
        }
        MinecraftJavaRequirement.parseVersion(versionId)?.let { return formatParsed(it) }
        // e.g. fabric-loader-0.14.22-1.16.5
        val trailing = Regex("""(\d+\.\d+(?:\.\d+)?)(?:$|[^0-9.])""")
            .findAll(versionId)
            .map { it.groupValues[1] }
            .lastOrNull()
        return trailing ?: versionId
    }

    private fun formatParsed(v: Triple<Int, Int, Int>): String =
        if (v.third == 0) "${v.first}.${v.second}" else "${v.first}.${v.second}.${v.third}"

    private data class ModFile(val url: String, val filename: String)

    private fun resolveDownload(gameVersion: String, loader: String): ModFile? {
        exactVersions(gameVersion, loader).firstOrNull()?.let { return primaryFile(it) }

        // Exact miss (common for 1.16.1 / pre-1.16.2): pick nearest published build.
        val all = allLoaderVersions(loader)
        if (all.isEmpty()) return null
        val target = MinecraftJavaRequirement.parseVersion(gameVersion)
        val best = all.mapNotNull { ver ->
            val games = ver.optJSONArray("game_versions") ?: JSONArray()
            var bestDist = Int.MAX_VALUE
            var label = ""
            for (i in 0 until games.length()) {
                val gv = games.optString(i)
                val parsed = MinecraftJavaRequirement.parseVersion(gv) ?: continue
                val dist = if (target == null) {
                    1_000_000
                } else {
                    versionDistance(target, parsed)
                }
                if (dist < bestDist) {
                    bestDist = dist
                    label = gv
                }
            }
            if (bestDist == Int.MAX_VALUE) null else Triple(bestDist, label, ver)
        }.minByOrNull { it.first }

        return best?.let { primaryFile(it.third) }
    }

    /** Prefer same major.minor, then nearest patch; otherwise absolute distance. */
    fun versionDistance(target: Triple<Int, Int, Int>, candidate: Triple<Int, Int, Int>): Int {
        val (a, b, c) = target
        val (x, y, z) = candidate
        return when {
            a == x && b == y -> kotlin.math.abs(c - z)
            a == x -> 100 + kotlin.math.abs(b - y) * 20 + kotlin.math.abs(c - z)
            else -> 10_000 + kotlin.math.abs(a - x) * 1_000 +
                kotlin.math.abs(b - y) * 20 + kotlin.math.abs(c - z)
        }
    }

    private fun exactVersions(gameVersion: String, loader: String): List<JSONObject> {
        val gv = URLEncoder.encode("\"$gameVersion\"", StandardCharsets.UTF_8.name())
        val ld = URLEncoder.encode("\"$loader\"", StandardCharsets.UTF_8.name())
        val url = "$API?game_versions=[$gv]&loaders=[$ld]"
        return fetchVersionArray(url)
    }

    private fun allLoaderVersions(loader: String): List<JSONObject> {
        val ld = URLEncoder.encode("\"$loader\"", StandardCharsets.UTF_8.name())
        val url = "$API?loaders=[$ld]&limit=100"
        return fetchVersionArray(url)
    }

    private fun fetchVersionArray(url: String): List<JSONObject> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.USER_AGENT)
            .get()
            .build()
        HttpClients.shared.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            val arr = JSONArray(body)
            return buildList {
                for (i in 0 until arr.length()) add(arr.getJSONObject(i))
            }
        }
    }

    private fun primaryFile(version: JSONObject): ModFile? {
        val files = version.optJSONArray("files") ?: return null
        var fallback: ModFile? = null
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val url = f.optString("url")
            val name = f.optString("filename")
            if (url.isBlank() || name.isBlank()) continue
            val mod = ModFile(url, name)
            if (f.optBoolean("primary", false)) return mod
            if (fallback == null) fallback = mod
        }
        return fallback
    }

    private fun download(url: String, dest: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.USER_AGENT)
            .get()
            .build()
        HttpClients.shared.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("下载 mcwifipnp 失败: HTTP ${response.code}")
            }
            val bytes = response.body?.bytes() ?: error("下载 mcwifipnp 失败: 空响应")
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, "${dest.name}.part")
            tmp.writeBytes(bytes)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                dest.writeBytes(bytes)
                tmp.delete()
            }
        }
    }
}
