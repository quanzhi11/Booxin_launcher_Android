package com.booxin.launcher.core.download.game

import com.booxin.launcher.core.LauncherPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Merges a mod-loader version JSON with its [inheritsFrom] chain (HMCL/FCL/PC launcher pattern).
 */
object VersionJsonMerger {

    fun readVersionJson(versionId: String): JSONObject? {
        val file = versionJsonFile(versionId) ?: return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    fun versionJsonFile(versionId: String): File? {
        val file = File(LauncherPaths.versionsDir, "$versionId/$versionId.json")
        return file.takeIf { it.isFile }
    }

    fun merge(versionId: String): JSONObject? {
        val root = readVersionJson(versionId) ?: return null
        return mergeChain(root, versionId, mutableSetOf())
    }

    fun resolveInheritsFrom(versionId: String): String? {
        return readVersionJson(versionId)?.optString("inheritsFrom")?.ifBlank { null }
    }

    fun resolveMinecraftVersionId(versionId: String): String {
        var current = versionId
        val visited = mutableSetOf<String>()
        while (visited.add(current)) {
            val parent = resolveInheritsFrom(current)
            if (parent.isNullOrBlank()) return current
            current = parent
        }
        return current
    }

    fun resolveClientJar(versionId: String): File? {
        val self = File(LauncherPaths.versionsDir, "$versionId/$versionId.jar")
        if (self.isFile && self.length() > 0L) return self
        var current = versionId
        val visited = mutableSetOf<String>()
        while (visited.add(current)) {
            val jar = File(LauncherPaths.versionsDir, "$current/$current.jar")
            if (jar.isFile && jar.length() > 0L) return jar
            val parent = resolveInheritsFrom(current) ?: break
            current = parent
        }
        return null
    }

    private fun mergeChain(
        current: JSONObject,
        versionId: String,
        visited: MutableSet<String>
    ): JSONObject {
        val inheritName = current.optString("inheritsFrom").ifBlank { null }
        if (inheritName == null || !visited.add(inheritName)) {
            return JSONObject(current.toString())
        }

        val parentFile = versionJsonFile(inheritName)
            ?: return JSONObject(current.toString())
        val parent = runCatching { JSONObject(parentFile.readText()) }.getOrNull()
            ?: return JSONObject(current.toString())

        val mergedParent = mergeChain(parent, inheritName, visited)
        val childLibraries = current.optJSONArray("libraries") ?: JSONArray()
        val mergedLibraries = JSONArray()
        appendLibraries(mergedLibraries, mergedParent.optJSONArray("libraries"))
        appendLibraries(mergedLibraries, childLibraries)

        val result = JSONObject(mergedParent.toString())
        val keys = current.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "libraries") continue
            result.put(key, current.get(key))
        }
        result.put("libraries", mergedLibraries)
        result.put("id", versionId)
        return result
    }

    private fun appendLibraries(target: JSONArray, source: JSONArray?) {
        if (source == null) return
        for (i in 0 until source.length()) {
            target.put(source.getJSONObject(i))
        }
    }
}
