package com.booxin.launcher.core.pluginstore

import android.content.Context
import com.booxin.launcher.R
import org.json.JSONArray

/**
 * Marketplace platform compatibility (aligned with server `/api/plugin-platforms`).
 * Aliases: phone/mobile → android; windows/pc/win → desktop.
 * Missing / empty platforms means compatible with both.
 */
object PluginStorePlatforms {
    const val ANDROID = "android"
    const val DESKTOP = "desktop"

    val ALL: List<String> = listOf(ANDROID, DESKTOP)

    fun normalizeToken(raw: String): String? =
        when (raw.trim().lowercase()) {
            "android", "phone", "mobile" -> ANDROID
            "desktop", "windows", "pc", "win" -> DESKTOP
            else -> null
        }

    /** Normalize aliases; empty / null → both platforms. */
    fun normalize(raw: Collection<String>?): List<String> {
        if (raw.isNullOrEmpty()) return ALL
        val set = linkedSetOf<String>()
        for (item in raw) {
            normalizeToken(item)?.let { set.add(it) }
        }
        return if (set.isEmpty()) ALL else set.toList()
    }

    fun supports(platforms: List<String>, platform: String): Boolean {
        val target = normalizeToken(platform) ?: platform.trim().lowercase()
        return normalize(platforms).contains(target)
    }

    fun supportsAndroid(platforms: List<String>): Boolean =
        supports(platforms, ANDROID)

    fun label(context: Context, platforms: List<String>): String {
        val n = normalize(platforms)
        val hasAndroid = n.contains(ANDROID)
        val hasDesktop = n.contains(DESKTOP)
        return when {
            hasAndroid && hasDesktop ->
                context.getString(R.string.plugin_store_platforms_both)
            hasAndroid ->
                context.getString(R.string.plugin_store_platforms_android_only)
            hasDesktop ->
                context.getString(R.string.plugin_store_platforms_desktop_only)
            else ->
                context.getString(R.string.plugin_store_platforms_both)
        }
    }

    fun parseJsonArray(arr: JSONArray?): List<String> {
        if (arr == null) return ALL
        val raw = buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).orEmpty()
                if (s.isNotBlank()) add(s)
            }
        }
        return normalize(raw)
    }
}
