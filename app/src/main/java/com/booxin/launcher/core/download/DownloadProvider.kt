package com.booxin.launcher.core.download

/**
 * Download source strategy inspired by FCL/HMCL DownloadProvider.
 */
enum class DownloadSource(val displayName: String) {
    OFFICIAL("官方源"),
    BALANCED("自动（官方优先）"),
    MIRROR("BMCLAPI 镜像")
}

interface DownloadProvider {
    fun versionListUrl(): String
    fun injectUrl(url: String): String
    fun assetObjectUrl(hashPath: String): String
}

object MojangDownloadProvider : DownloadProvider {
    override fun versionListUrl(): String =
        "https://piston-meta.mojang.com/mc/game/version_manifest.json"

    override fun injectUrl(url: String): String = url

    override fun assetObjectUrl(hashPath: String): String =
        "https://resources.download.minecraft.net/$hashPath"
}

class BmclApiDownloadProvider(
    private val apiRoot: String = DEFAULT_API_ROOT
) : DownloadProvider {

    override fun versionListUrl(): String = "$apiRoot/mc/game/version_manifest.json"

    override fun injectUrl(url: String): String {
        var result = url
        for ((from, to) in REPLACEMENTS) {
            if (result.startsWith(from)) {
                result = to + result.removePrefix(from)
                break
            }
        }
        return result
    }

    override fun assetObjectUrl(hashPath: String): String = "$apiRoot/assets/$hashPath"

    companion object {
        const val DEFAULT_API_ROOT = "https://bmclapi2.bangbang93.com"

        private val REPLACEMENTS = listOf(
            "https://launchermeta.mojang.com" to DEFAULT_API_ROOT,
            "https://piston-meta.mojang.com" to DEFAULT_API_ROOT,
            "https://piston-data.mojang.com" to DEFAULT_API_ROOT,
            "https://launcher.mojang.com" to DEFAULT_API_ROOT,
            "https://libraries.minecraft.net" to "$DEFAULT_API_ROOT/libraries",
            "https://resources.download.minecraft.net" to "$DEFAULT_API_ROOT/assets"
        )
    }
}

/**
 * File downloads try primary then fallback (FCL "official/balanced" file strategy).
 */
class CascadeDownloadProvider(
    private val primary: DownloadProvider,
    private val fallback: DownloadProvider? = null
) : DownloadProvider {
    override fun versionListUrl(): String = primary.versionListUrl()
    override fun injectUrl(url: String): String = primary.injectUrl(url)
    override fun assetObjectUrl(hashPath: String): String = primary.assetObjectUrl(hashPath)

    fun candidateUrls(rawUrl: String): List<String> {
        val first = primary.injectUrl(rawUrl)
        val second = fallback?.injectUrl(rawUrl)
        return listOfNotNull(first, second).distinct()
    }

    fun versionListCandidates(): List<String> {
        val first = primary.versionListUrl()
        val second = fallback?.versionListUrl()
        return listOfNotNull(first, second).distinct()
    }

    fun assetCandidates(hashPath: String): List<String> {
        val first = primary.assetObjectUrl(hashPath)
        val second = fallback?.assetObjectUrl(hashPath)
        return listOfNotNull(first, second).distinct()
    }
}

object DownloadProviders {
    @Volatile
    var source: DownloadSource = DownloadSource.BALANCED

    fun current(): CascadeDownloadProvider {
        val bmcl = BmclApiDownloadProvider()
        return when (source) {
            DownloadSource.OFFICIAL -> CascadeDownloadProvider(MojangDownloadProvider, bmcl)
            DownloadSource.BALANCED -> CascadeDownloadProvider(MojangDownloadProvider, bmcl)
            DownloadSource.MIRROR -> CascadeDownloadProvider(bmcl, MojangDownloadProvider)
        }
    }
}
