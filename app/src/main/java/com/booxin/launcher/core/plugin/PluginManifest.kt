package com.booxin.launcher.core.plugin

import org.json.JSONObject
import java.io.File

/** On-disk / in-APK `plugin.json` for imported plugins. */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val type: PluginType,
    val glLib: String,
    val eglLib: String = "libEGL.so",
    val rendererToken: String = "opengles3",
    val libGlEs: String = "3",
    val extraEnv: Map<String, String> = emptyMap(),
    val kindName: String? = null,
    val disguiseAsGl4es: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("type", type.name)
        put("glLib", glLib)
        put("eglLib", eglLib)
        put("rendererToken", rendererToken)
        put("libGlEs", libGlEs)
        put("disguiseAsGl4es", disguiseAsGl4es)
        kindName?.let { put("kindName", it) }
        if (extraEnv.isNotEmpty()) {
            put("extraEnv", JSONObject(extraEnv))
        }
    }

    companion object {
        fun read(file: File): PluginManifest? {
            if (!file.isFile) return null
            return parse(file.readText())
        }

        fun parse(text: String): PluginManifest? = runCatching {
            val o = JSONObject(text)
            val id = o.optString("id").trim()
            val glLib = o.optString("glLib").trim()
            if (id.isEmpty() || glLib.isEmpty()) return@runCatching null
            val type = runCatching {
                PluginType.valueOf(o.optString("type", PluginType.RENDERER.name))
            }.getOrDefault(PluginType.RENDERER)
            val envObj = o.optJSONObject("extraEnv")
            val env = linkedMapOf<String, String>()
            if (envObj != null) {
                val keys = envObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    env[k] = envObj.optString(k)
                }
            }
            PluginManifest(
                id = id,
                name = o.optString("name").ifBlank { id },
                version = o.optString("version").ifBlank { "1.0" },
                type = type,
                glLib = glLib,
                eglLib = o.optString("eglLib").ifBlank { "libEGL.so" },
                rendererToken = o.optString("rendererToken").ifBlank { "opengles3" },
                libGlEs = o.optString("libGlEs").ifBlank { "3" },
                extraEnv = env,
                kindName = o.optString("kindName").takeIf { it.isNotBlank() },
                disguiseAsGl4es = o.optBoolean("disguiseAsGl4es", false)
            )
        }.getOrNull()
    }
}
