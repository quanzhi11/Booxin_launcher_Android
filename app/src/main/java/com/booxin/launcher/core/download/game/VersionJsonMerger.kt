package com.booxin.launcher.core.download.game

import com.booxin.launcher.core.LauncherPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Merges a mod-loader version JSON with its [inheritsFrom] chain (HMCL/FCL/PC launcher pattern).
 *
 * FCL [Version.merge]: child libraries first, then parent; [Arguments.merge] concatenates
 * parent+child jvm/game argument lists (child must not replace parent game args).
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
        // Forge may set "jar": "1.21.11" pointing at parent client jar name.
        readVersionJson(versionId)?.optString("jar")?.ifBlank { null }?.let { jarId ->
            val named = File(LauncherPaths.versionsDir, "$jarId/$jarId.jar")
            if (named.isFile && named.length() > 0L) return named
        }
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

    fun isModLoaderVersion(versionId: String): Boolean {
        return !resolveInheritsFrom(versionId).isNullOrBlank()
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

        // FCL Version.merge: Lang.merge(this.libraries, parent.libraries)
        // = concatenate child then parent. NO group:artifact dedupe
        // (classifiers like forge:client vs forge:universal must both remain).
        // Path duplicates are collapsed later via LinkedHashSet classpath (FCL getClasspath).
        val mergedLibraries = JSONArray()
        appendAll(mergedLibraries, current.optJSONArray("libraries"))
        appendAll(mergedLibraries, mergedParent.optJSONArray("libraries"))

        val result = JSONObject(mergedParent.toString())
        val keys = current.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (key) {
                "libraries", "inheritsFrom", "arguments" -> continue
                else -> result.put(key, current.get(key))
            }
        }
        result.put("libraries", mergedLibraries)
        result.put("arguments", mergeArguments(mergedParent.optJSONObject("arguments"), current.optJSONObject("arguments")))
        result.put("id", versionId)
        result.remove("inheritsFrom")
        return result
    }

    /** FCL Arguments.merge: concatenate parent then child for game/jvm lists. */
    private fun mergeArguments(parent: JSONObject?, child: JSONObject?): JSONObject? {
        if (parent == null) return child?.let { JSONObject(it.toString()) }
        if (child == null) return JSONObject(parent.toString())
        val result = JSONObject()
        result.put("jvm", concatJsonArrays(parent.optJSONArray("jvm"), child.optJSONArray("jvm")))
        result.put("game", concatJsonArrays(parent.optJSONArray("game"), child.optJSONArray("game")))
        return result
    }

    private fun concatJsonArrays(a: JSONArray?, b: JSONArray?): JSONArray {
        val out = JSONArray()
        if (a != null) for (i in 0 until a.length()) out.put(a.get(i))
        if (b != null) for (i in 0 until b.length()) out.put(b.get(i))
        return out
    }

    private fun appendAll(target: JSONArray, source: JSONArray?) {
        if (source == null) return
        for (i in 0 until source.length()) target.put(source.get(i))
    }
}
