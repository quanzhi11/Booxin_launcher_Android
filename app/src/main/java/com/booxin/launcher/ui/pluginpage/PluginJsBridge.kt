package com.booxin.launcher.ui.pluginpage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.navigation.NavController
import com.booxin.launcher.BuildConfig
import com.booxin.launcher.R
import com.booxin.launcher.core.uiplugin.UiPluginPermissionStore
import org.json.JSONObject

/**
 * Allowlisted JS bridge for plugin pages. Never exposes shell / filesystem / native game APIs.
 * Requires [UiPluginPermissionStore] grant when the plugin declared `features.jsCommands`.
 */
class PluginJsBridge(
    private val appContext: Context,
    private val pluginId: String,
    private val pageId: String,
    private val requiresGrant: Boolean,
    private val navController: () -> NavController?,
    private val onClose: () -> Unit
) {
    @JavascriptInterface
    fun getInfo(): String {
        ensureAllowed("getInfo")
        return JSONObject()
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("pluginId", pluginId)
            .put("pageId", pageId)
            .put("jsCommandsGranted", isGranted())
            .toString()
    }

    @JavascriptInterface
    fun toast(message: String?) {
        ensureAllowed("toast")
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return
        val safe = text.take(200)
        android.os.Handler(appContext.mainLooper).post {
            Toast.makeText(appContext, safe, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun openUrl(url: String?): String {
        ensureAllowed("openUrl")
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return err("empty url")
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return err("bad url")
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "https" && scheme != "http") return err("only http/https")
        android.os.Handler(appContext.mainLooper).post {
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        return ok()
    }

    @JavascriptInterface
    fun navigate(destination: String?): String {
        ensureAllowed("navigate")
        val key = destination?.trim()?.lowercase().orEmpty()
        val destId = when (key) {
            "home" -> R.id.nav_home
            "versions" -> R.id.nav_versions
            "community" -> R.id.nav_community
            "multiplayer" -> R.id.nav_multiplayer
            "ai" -> R.id.nav_ai
            "pluginstore", "plugin_store", "plugins" -> R.id.nav_plugin_store
            "settings" -> R.id.nav_settings
            else -> return err("unknown destination")
        }
        android.os.Handler(appContext.mainLooper).post {
            navController()?.navigate(destId)
        }
        return ok()
    }

    @JavascriptInterface
    fun close(): String {
        ensureAllowed("close")
        android.os.Handler(appContext.mainLooper).post { onClose() }
        return ok()
    }

    private fun isGranted(): Boolean {
        if (!requiresGrant) return true
        return UiPluginPermissionStore.isJsCommandsGranted(appContext, pluginId)
    }

    private fun ensureAllowed(op: String) {
        if (isGranted()) return
        throw SecurityException("jsCommands not granted for $pluginId ($op)")
    }

    private fun ok(): String = JSONObject().put("ok", true).toString()
    private fun err(msg: String): String =
        JSONObject().put("ok", false).put("error", msg).toString()
}
