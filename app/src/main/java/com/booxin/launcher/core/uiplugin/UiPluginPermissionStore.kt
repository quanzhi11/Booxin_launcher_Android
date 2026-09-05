package com.booxin.launcher.core.uiplugin

import android.content.Context

/**
 * Per-plugin capability grants. Declaring [UiPluginManifest.jsCommands] is not enough —
 * the user must approve before the JS bridge is live.
 */
object UiPluginPermissionStore {

    private const val PREFS = "booxin_ui_plugin_permissions"
    private const val KEY_JS_PREFIX = "js_commands:"

    fun isJsCommandsGranted(context: Context, pluginId: String): Boolean {
        val id = pluginId.trim()
        if (id.isEmpty()) return false
        return prefs(context).getBoolean(KEY_JS_PREFIX + id, false)
    }

    fun setJsCommandsGranted(context: Context, pluginId: String, granted: Boolean) {
        val id = pluginId.trim()
        if (id.isEmpty()) return
        prefs(context).edit().putBoolean(KEY_JS_PREFIX + id, granted).apply()
    }

    fun revokeAllForPlugin(context: Context, pluginId: String) {
        val id = pluginId.trim()
        if (id.isEmpty()) return
        prefs(context).edit().remove(KEY_JS_PREFIX + id).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
