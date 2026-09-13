package com.booxin.launcher.core.version

import com.booxin.launcher.core.LauncherPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Launcher-only instance UI metadata (aligns with PC CustomDisplayName / Game.IsDefault).
 * Does **not** rename `versions/<id>` folders. Sort order is phone-only.
 */
object VersionUiMetaStore {
    private const val FILE_NAME = "version_ui_meta.json"
    private const val SETUP_DIR = "Booxin"
    private const val SETUP_FILE = "Setup.ini"

    data class Meta(
        val aliases: Map<String, String> = emptyMap(),
        val order: List<String> = emptyList(),
        val defaultVersionId: String? = null,
        val selectedVersionId: String? = null
    )

    @Synchronized
    fun load(): Meta {
        val f = file()
        if (!f.isFile) {
            // Seed aliases from Setup.ini if present.
            return Meta(aliases = scanSetupAliases())
        }
        return runCatching {
            val o = JSONObject(f.readText(StandardCharsets.UTF_8))
            val aliases = mutableMapOf<String, String>()
            val aliasObj = o.optJSONObject("aliases")
            if (aliasObj != null) {
                aliasObj.keys().forEach { key ->
                    val v = aliasObj.optString(key).trim()
                    if (v.isNotEmpty()) aliases[key] = v
                }
            }
            // Merge Setup.ini aliases for ids not yet in JSON.
            scanSetupAliases().forEach { (id, name) ->
                if (!aliases.containsKey(id)) aliases[id] = name
            }
            val order = mutableListOf<String>()
            val arr = o.optJSONArray("order")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i).trim()
                    if (id.isNotEmpty()) order += id
                }
            }
            Meta(
                aliases = aliases,
                order = order,
                defaultVersionId = o.optString("defaultVersionId").trim().ifBlank { null },
                selectedVersionId = o.optString("selectedVersionId").trim().ifBlank { null }
            )
        }.getOrDefault(Meta(aliases = scanSetupAliases()))
    }

    @Synchronized
    fun save(meta: Meta) {
        val aliasesJson = JSONObject()
        meta.aliases.forEach { (id, name) ->
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) aliasesJson.put(id, trimmed)
        }
        val orderJson = JSONArray()
        meta.order.forEach { orderJson.put(it) }
        val root = JSONObject()
            .put("aliases", aliasesJson)
            .put("order", orderJson)
            .put("defaultVersionId", meta.defaultVersionId)
            .put("selectedVersionId", meta.selectedVersionId)
        val f = file()
        f.parentFile?.mkdirs()
        f.writeText(root.toString(2), StandardCharsets.UTF_8)
    }

    fun displayName(versionId: String, meta: Meta = load()): String {
        val alias = meta.aliases[versionId]?.trim().orEmpty()
        if (alias.isNotEmpty()) return alias
        return versionId
    }

    fun setAlias(versionId: String, displayName: String?) {
        val id = versionId.trim()
        if (id.isEmpty()) return
        val cur = load()
        val nextAliases = cur.aliases.toMutableMap()
        val trimmed = displayName?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == id) {
            nextAliases.remove(id)
            writeSetupAlias(id, null)
        } else {
            nextAliases[id] = trimmed
            writeSetupAlias(id, trimmed)
        }
        save(cur.copy(aliases = nextAliases))
    }

    fun setDefault(versionId: String?) {
        val cur = load()
        val id = versionId?.trim()?.ifBlank { null }
        save(cur.copy(defaultVersionId = id))
    }

    fun setSelected(versionId: String?) {
        val cur = load()
        val id = versionId?.trim()?.ifBlank { null }
        save(cur.copy(selectedVersionId = id))
    }

    fun setOrder(orderedIds: List<String>) {
        val cur = load()
        save(cur.copy(order = orderedIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()))
    }

    fun removeVersion(versionId: String) {
        val id = versionId.trim()
        if (id.isEmpty()) return
        val cur = load()
        save(
            cur.copy(
                aliases = cur.aliases - id,
                order = cur.order.filterNot { it == id },
                defaultVersionId = cur.defaultVersionId?.takeUnless { it == id },
                selectedVersionId = cur.selectedVersionId?.takeUnless { it == id }
            )
        )
    }

    fun sortInstalled(ids: List<String>, meta: Meta = load()): List<String> {
        if (ids.isEmpty()) return emptyList()
        val remaining = ids.toMutableList()
        val sorted = mutableListOf<String>()
        meta.order.forEach { id ->
            if (remaining.remove(id)) sorted += id
        }
        // Unknown (new installs): keep caller order (typically lastModified desc).
        sorted += remaining
        return sorted
    }

    private fun file(): File = File(LauncherPaths.rootDir, FILE_NAME)

    private fun setupIni(versionId: String): File =
        File(LauncherPaths.versionsDir, "$versionId/$SETUP_DIR/$SETUP_FILE")

    private fun writeSetupAlias(versionId: String, alias: String?) {
        val ini = setupIni(versionId)
        runCatching {
            ini.parentFile?.mkdirs()
            val existing = if (ini.isFile) ini.readLines(StandardCharsets.UTF_8) else emptyList()
            val out = mutableListOf<String>()
            var hasSetup = false
            var wroteAlias = false
            if (existing.isEmpty()) {
                out += "[Setup]"
                out += "Alias=${alias.orEmpty()}"
                ini.writeText(out.joinToString("\n") + "\n", StandardCharsets.UTF_8)
                return
            }
            for (line in existing) {
                val trimmed = line.trim()
                if (trimmed.equals("[Setup]", ignoreCase = true)) {
                    hasSetup = true
                    out += line
                    continue
                }
                if (trimmed.startsWith("Alias=", ignoreCase = true) ||
                    trimmed.startsWith("CustomDisplayName=", ignoreCase = true)
                ) {
                    if (!wroteAlias) {
                        out += "Alias=${alias.orEmpty()}"
                        wroteAlias = true
                    }
                    continue
                }
                out += line
            }
            if (!hasSetup) {
                out.add(0, "[Setup]")
            }
            if (!wroteAlias) {
                val setupIdx = out.indexOfFirst { it.trim().equals("[Setup]", ignoreCase = true) }
                if (setupIdx >= 0) {
                    out.add(setupIdx + 1, "Alias=${alias.orEmpty()}")
                } else {
                    out += "Alias=${alias.orEmpty()}"
                }
            }
            ini.writeText(out.joinToString("\n") + "\n", StandardCharsets.UTF_8)
        }
    }

    private fun scanSetupAliases(): Map<String, String> {
        val dirs = LauncherPaths.versionsDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        val map = mutableMapOf<String, String>()
        dirs.forEach { dir ->
            val alias = readSetupAlias(dir.name) ?: return@forEach
            map[dir.name] = alias
        }
        return map
    }

    private fun readSetupAlias(versionId: String): String? {
        val ini = setupIni(versionId)
        if (!ini.isFile) return null
        return runCatching {
            ini.readLines(StandardCharsets.UTF_8).firstNotNullOfOrNull { line ->
                val t = line.trim()
                when {
                    t.startsWith("Alias=", ignoreCase = true) ->
                        t.substringAfter('=').trim().ifBlank { null }
                    t.startsWith("CustomDisplayName=", ignoreCase = true) ->
                        t.substringAfter('=').trim().ifBlank { null }
                    else -> null
                }
            }
        }.getOrNull()
    }
}
