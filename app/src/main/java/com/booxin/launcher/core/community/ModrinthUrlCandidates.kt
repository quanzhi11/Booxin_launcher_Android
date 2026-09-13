package com.booxin.launcher.core.community

import com.booxin.launcher.core.download.DownloadProviders
import com.booxin.launcher.core.download.DownloadSource
import com.booxin.launcher.core.download.MirrorPreference

/**
 * Modrinth API / CDN URL candidates.
 *
 * File downloads must not depend solely on [MCIM_HOST]: the mirror often 302s back
 * to cdn.modrinth.com, and its DNS fails on some CN mobile networks.
 */
object ModrinthUrlCandidates {
    const val API_OFFICIAL = "https://api.modrinth.com"
    const val API_MIRROR = "https://mod.mcimirror.top/modrinth"
    const val CDN_OFFICIAL = "https://cdn.modrinth.com"
    const val CDN_ALT = "https://cdn-alt.modrinth.com"
    const val MCIM_HOST = "https://mod.mcimirror.top"

    fun api(path: String): List<String> {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val official = "$API_OFFICIAL$normalized"
        val mirror = "$API_MIRROR$normalized"
        // MCIM Modrinth API often 404 on mobile; always try official first.
        return listOf(official, mirror)
    }

    /**
     * Expand a Modrinth file URL into CDN / mirror candidates.
     * On CN (mirror-first prefs) try MCIM before cdn.modrinth.com so we do not
     * burn cascade timeouts on an unreachable official host for every jar.
     */
    fun fileDownloads(rawUrl: String, mirrorFirst: Boolean = preferMirrorFirst()): List<String> {
        val input = rawUrl.trim()
        if (input.isEmpty()) return emptyList()
        val path = extractCdnPath(input)
        if (path == null) return listOf(input)

        val official = "$CDN_OFFICIAL/$path"
        val alt = "$CDN_ALT/$path"
        val mirror = "$MCIM_HOST/$path"
        return if (mirrorFirst) {
            listOf(mirror, official, alt, input).distinct()
        } else {
            listOf(official, alt, mirror, input).distinct()
        }
    }

    fun preferMirrorFirst(): Boolean = when (DownloadProviders.source) {
        DownloadSource.MIRROR -> true
        DownloadSource.OFFICIAL -> false
        DownloadSource.BALANCED ->
            DownloadProviders.libraryPreference == MirrorPreference.MIRROR_FIRST
    }

    private fun extractCdnPath(url: String): String? {
        val prefixes = listOf(
            "https://cdn.modrinth.com/",
            "http://cdn.modrinth.com/",
            "https://cdn-alt.modrinth.com/",
            "http://cdn-alt.modrinth.com/",
            "https://mod.mcimirror.top/",
            "http://mod.mcimirror.top/"
        )
        for (prefix in prefixes) {
            if (url.startsWith(prefix, ignoreCase = true)) {
                val path = url.substring(prefix.length).trimStart('/')
                // Skip MCIM API paths — those are not file CDN objects.
                if (path.startsWith("modrinth/", ignoreCase = true) ||
                    path.startsWith("curseforge/", ignoreCase = true) ||
                    path.startsWith("translate/", ignoreCase = true)
                ) {
                    return null
                }
                return path.takeIf { it.isNotBlank() }
            }
        }
        return null
    }
}
