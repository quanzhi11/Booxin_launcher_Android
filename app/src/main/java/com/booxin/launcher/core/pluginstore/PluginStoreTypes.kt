package com.booxin.launcher.core.pluginstore

import android.content.Context
import com.booxin.launcher.R

/**
 * Marketplace plugin categories (aligned with server `/api/plugin-types`).
 */
object PluginStoreTypes {
    data class Entry(val id: String, val labelRes: Int)

    val ALL = listOf(
        Entry("renderer", R.string.plugins_type_renderer),
        Entry("driver", R.string.plugins_type_driver),
        Entry("ui", R.string.plugin_store_type_ui),
        Entry("control", R.string.plugin_store_type_control),
        Entry("input", R.string.plugin_store_type_input),
        Entry("overlay", R.string.plugin_store_type_overlay),
        Entry("utility", R.string.plugin_store_type_utility),
        Entry("pack", R.string.plugin_store_type_pack),
        Entry("other", R.string.plugin_store_type_other)
    )

    fun label(context: Context, typeId: String): String {
        val entry = ALL.firstOrNull { it.id.equals(typeId, ignoreCase = true) }
            ?: ALL.last()
        return context.getString(entry.labelRes)
    }

    fun labels(context: Context): List<String> =
        ALL.map { context.getString(it.labelRes) }

    fun idAt(index: Int): String =
        ALL.getOrNull(index)?.id ?: "other"
}
