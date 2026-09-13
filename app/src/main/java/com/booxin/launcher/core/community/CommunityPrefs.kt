package com.booxin.launcher.core.community

import android.content.Context
import com.booxin.launcher.data.model.CommunitySource

object CommunityPrefs {
    private const val PREFS = "community_prefs"
    private const val KEY_SOURCE = "browse_source"
    private const val KEY_GUIDE_SHOWN = "first_open_guide_v1"

    fun getSource(context: Context): CommunitySource {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE, CommunitySource.MODRINTH.name)
            ?: CommunitySource.MODRINTH.name
        return runCatching { CommunitySource.valueOf(raw) }
            .getOrDefault(CommunitySource.MODRINTH)
    }

    fun setSource(context: Context, source: CommunitySource) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE, source.name)
            .apply()
    }

    fun hasShownFirstOpenGuide(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_GUIDE_SHOWN, false)

    fun markFirstOpenGuideShown(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GUIDE_SHOWN, true)
            .apply()
    }
}
