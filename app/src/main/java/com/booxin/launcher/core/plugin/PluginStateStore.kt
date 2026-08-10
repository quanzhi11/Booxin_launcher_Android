package com.booxin.launcher.core.plugin

import com.booxin.launcher.core.LauncherPaths
import org.json.JSONObject
import java.io.File

/** Persists enabled/disabled flags for plugins. Missing id → enabled by default. */
object PluginStateStore {
    private fun stateFile(): File =
        File(LauncherPaths.runtimeDir, "plugins-state.json")

    fun isEnabled(id: String): Boolean {
        val map = load()
        if (!map.has(id)) return true
        return map.optBoolean(id, true)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val map = load()
        map.put(id, enabled)
        stateFile().parentFile?.mkdirs()
        stateFile().writeText(map.toString())
    }

    fun remove(id: String) {
        val map = load()
        map.remove(id)
        stateFile().writeText(map.toString())
    }

    private fun load(): JSONObject {
        val f = stateFile()
        if (!f.isFile) return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
    }
}
