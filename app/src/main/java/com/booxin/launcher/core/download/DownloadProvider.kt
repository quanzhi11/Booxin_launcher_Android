package com.booxin.launcher.core.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Download source strategy (primary + fallback mirrors).
 */
enum class DownloadSource(val displayName: String) {
    OFFICIAL("官方源"),
    BALANCED("自动（手机测速）"),
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
            "https://resources.download.minecraft.net" to "$DEFAULT_API_ROOT/assets",
            // Forge 处理器依赖国内官方 Maven 常 403。
            "https://maven.minecraftforge.net" to "$DEFAULT_API_ROOT/maven",
            "https://files.minecraftforge.net/maven" to "$DEFAULT_API_ROOT/maven",
            // NeoForge libs / installer artifacts.
            "https://maven.neoforged.net/releases" to "$DEFAULT_API_ROOT/maven",
            "https://maven.neoforged.net" to "$DEFAULT_API_ROOT/maven",
            // Fabric loader / intermediary / mixin (profile libraries use this base).
            "https://maven.fabricmc.net/" to "$DEFAULT_API_ROOT/maven/",
            "https://maven.fabricmc.net" to "$DEFAULT_API_ROOT/maven",
            // Quilt Maven；BMCL 缺货时回退官方。
            "https://maven.quiltmc.org/repository/release/" to "$DEFAULT_API_ROOT/maven/",
            "https://maven.quiltmc.org/repository/release" to "$DEFAULT_API_ROOT/maven",
            "https://maven.quiltmc.org/repository/snapshot/" to "$DEFAULT_API_ROOT/maven/",
            "https://maven.quiltmc.org/repository/snapshot" to "$DEFAULT_API_ROOT/maven"
        )
    }
}

/**
 * File downloads try primary then fallback.
 */
class CascadeDownloadProvider(
    private val primary: DownloadProvider,
    private val fallback: DownloadProvider? = null,
    private val libraryPreference: () -> MirrorPreference = { MirrorPreference.OFFICIAL_FIRST },
    private val forgePreference: () -> MirrorPreference = { MirrorPreference.MIRROR_FIRST }
) : DownloadProvider {
    override fun versionListUrl(): String = primary.versionListUrl()
    override fun injectUrl(url: String): String = primary.injectUrl(url)
    override fun assetObjectUrl(hashPath: String): String = primary.assetObjectUrl(hashPath)

    fun candidateUrls(rawUrl: String): List<String> {
        val official = MojangDownloadProvider.injectUrl(rawUrl)
        val bmcl = BmclApiDownloadProvider().injectUrl(rawUrl)
        val extras = mavenCentralFallback(rawUrl)
        val preferMirror = if (isForgeMavenUrl(rawUrl)) {
            forgePreference() == MirrorPreference.MIRROR_FIRST
        } else {
            libraryPreference() == MirrorPreference.MIRROR_FIRST
        }
        val ordered = if (preferMirror) {
            listOfNotNull(bmcl, official.takeIf { it != bmcl }).plus(extras)
        } else {
            listOfNotNull(official, bmcl.takeIf { it != official }).plus(extras)
        }
        return ordered.distinct()
    }

    /**
     * Extra mirrors when Mojang / Forge Maven block or miss an artifact.
     * Example: Forge 1.20.1 ships asm:9.6 on libraries.minecraft.net but asm:9.2 /
     * trove on maven.minecraftforge.net (often 403).
     */
    private fun mavenCentralFallback(rawUrl: String): List<String> {
        val path = when {
            rawUrl.startsWith("https://maven.minecraftforge.net/") ->
                rawUrl.removePrefix("https://maven.minecraftforge.net/")
            rawUrl.startsWith("https://files.minecraftforge.net/maven/") ->
                rawUrl.removePrefix("https://files.minecraftforge.net/maven/")
            rawUrl.startsWith("https://maven.neoforged.net/releases/") ->
                rawUrl.removePrefix("https://maven.neoforged.net/releases/")
            rawUrl.startsWith("https://maven.neoforged.net/") ->
                rawUrl.removePrefix("https://maven.neoforged.net/")
            rawUrl.startsWith("https://maven.fabricmc.net/") ->
                rawUrl.removePrefix("https://maven.fabricmc.net/")
            rawUrl.startsWith("https://maven.quiltmc.org/repository/release/") ->
                rawUrl.removePrefix("https://maven.quiltmc.org/repository/release/")
            rawUrl.startsWith("https://maven.quiltmc.org/repository/snapshot/") ->
                rawUrl.removePrefix("https://maven.quiltmc.org/repository/snapshot/")
            rawUrl.startsWith("https://libraries.minecraft.net/") ->
                rawUrl.removePrefix("https://libraries.minecraft.net/")
            else -> return emptyList()
        }
        if (path.isBlank() ||
            path.contains("net/minecraftforge/forge/") ||
            path.contains("net/neoforged/neoforge/") ||
            path.contains("net/neoforged/forge/") ||
            path.contains("net/fabricmc/") ||
            path.contains("org/quiltmc/")
        ) {
            // Forge/NeoForge/Fabric/Quilt artifacts stay on their Maven/BMCL mirrors.
            return emptyList()
        }
        return listOf("https://repo1.maven.org/maven2/$path")
    }

    fun versionListCandidates(): List<String> {
        val official = MojangDownloadProvider.versionListUrl()
        val bmcl = BmclApiDownloadProvider().versionListUrl()
        return if (libraryPreference() == MirrorPreference.MIRROR_FIRST) {
            listOf(bmcl, official).distinct()
        } else {
            listOf(official, bmcl).distinct()
        }
    }

    fun assetCandidates(hashPath: String): List<String> {
        val official = MojangDownloadProvider.assetObjectUrl(hashPath)
        val bmcl = BmclApiDownloadProvider().assetObjectUrl(hashPath)
        return if (libraryPreference() == MirrorPreference.MIRROR_FIRST) {
            listOf(bmcl, official).distinct()
        } else {
            listOf(official, bmcl).distinct()
        }
    }

    private fun isForgeMavenUrl(rawUrl: String): Boolean =
        rawUrl.startsWith("https://maven.minecraftforge.net/") ||
            rawUrl.startsWith("https://files.minecraftforge.net/maven/") ||
            rawUrl.startsWith("https://maven.neoforged.net/") ||
            rawUrl.startsWith("https://maven.fabricmc.net/") ||
            rawUrl.startsWith("https://maven.quiltmc.org/")
}

object DownloadProviders {
    private const val TAG = "DownloadProviders"
    private const val PREFS = "booxin_download"
    private const val KEY_SOURCE = "source"
    private const val KEY_LIB_PREF = "lib_pref"
    private const val KEY_FORGE_PREF = "forge_pref"
    private const val KEY_SUMMARY = "probe_summary"
    private const val KEY_PROBED_AT = "probed_at"
    private const val PROBE_TTL_MS = 6L * 60L * 60L * 1000L
    /** Flip BALANCED preference after this many consecutive primary-source failures. */
    private const val ADAPTIVE_FLIP_THRESHOLD = 4

    private val probeMutex = Mutex()
    private val consecutivePrimaryFails = AtomicInteger(0)

    @Volatile
    var source: DownloadSource = DownloadSource.BALANCED
        private set

    @Volatile
    var libraryPreference: MirrorPreference = MirrorPreference.MIRROR_FIRST
        private set

    @Volatile
    var forgePreference: MirrorPreference = MirrorPreference.MIRROR_FIRST
        private set

    @Volatile
    var lastProbeSummary: String? = null
        private set

    @Volatile
    private var probedAtMs: Long = 0L

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        source = runCatching {
            DownloadSource.valueOf(prefs.getString(KEY_SOURCE, DownloadSource.BALANCED.name)!!)
        }.getOrDefault(DownloadSource.BALANCED)
        libraryPreference = readPref(prefs.getString(KEY_LIB_PREF, null), MirrorPreference.MIRROR_FIRST)
        forgePreference = readPref(prefs.getString(KEY_FORGE_PREF, null), MirrorPreference.MIRROR_FIRST)
        lastProbeSummary = prefs.getString(KEY_SUMMARY, null)
        probedAtMs = prefs.getLong(KEY_PROBED_AT, 0L)
        consecutivePrimaryFails.set(0)
        Log.i(TAG, "restored source=$source lib=$libraryPreference forge=$forgePreference")
    }

    fun setSource(context: Context, next: DownloadSource) {
        source = next
        consecutivePrimaryFails.set(0)
        persist(context)
    }

    fun needsProbe(): Boolean {
        if (source != DownloadSource.BALANCED) return false
        if (probedAtMs <= 0L) return true
        return System.currentTimeMillis() - probedAtMs > PROBE_TTL_MS
    }

    suspend fun ensureProbed(context: Context, force: Boolean = false): DownloadProbeResult? {
        if (!force && source != DownloadSource.BALANCED) return null
        return probeMutex.withLock {
            if (!force && source != DownloadSource.BALANCED) return@withLock null
            if (!force && !needsProbe()) return@withLock null
            val result = DownloadSourceProbe.probe()
            applyProbe(context, result)
            consecutivePrimaryFails.set(0)
            result
        }
    }

    fun applyProbe(context: Context, result: DownloadProbeResult) {
        libraryPreference = result.libraryPreference
        forgePreference = result.forgePreference
        lastProbeSummary = result.summary
        probedAtMs = result.probedAtMs
        persist(context)
        Log.i(TAG, "probe applied: ${result.summary}")
    }

    /**
     * Runtime feedback from cascade downloads. In BALANCED mode, repeated primary
     * failures flip preference so later files skip the dead host sooner.
     */
    fun noteDownloadOutcome(url: String, success: Boolean) {
        if (source != DownloadSource.BALANCED) return
        val mirror = isMirrorUrl(url)
        val forgeHost = isForgeHost(url)
        val primaryIsMirror = if (forgeHost) {
            forgePreference == MirrorPreference.MIRROR_FIRST
        } else {
            libraryPreference == MirrorPreference.MIRROR_FIRST
        }
        val isPrimary = mirror == primaryIsMirror
        if (success) {
            if (isPrimary) consecutivePrimaryFails.set(0)
            return
        }
        if (!isPrimary) return
        val fails = consecutivePrimaryFails.incrementAndGet()
        if (fails < ADAPTIVE_FLIP_THRESHOLD) return
        val flipped = if (primaryIsMirror) {
            MirrorPreference.OFFICIAL_FIRST
        } else {
            MirrorPreference.MIRROR_FIRST
        }
        if (forgeHost) {
            forgePreference = flipped
        } else {
            libraryPreference = flipped
        }
        consecutivePrimaryFails.set(0)
        lastProbeSummary = "运行中自动切换 → ${label(flipped)}（主源连续失败）"
        appContext?.let { persist(it) }
        Log.w(TAG, "adaptive flip to $flipped after $fails primary failures forge=$forgeHost")
    }

    private fun isForgeHost(url: String): Boolean =
        url.contains("minecraftforge.net", ignoreCase = true) ||
            url.contains("neoforged.net", ignoreCase = true) ||
            url.contains("fabricmc.net", ignoreCase = true) ||
            url.contains("quiltmc.org", ignoreCase = true) ||
            url.contains("/maven/", ignoreCase = true) && isMirrorUrl(url)

    fun statusText(): String = when (source) {
        DownloadSource.OFFICIAL -> "始终优先官方源，失败再试 BMCL"
        DownloadSource.MIRROR -> "始终优先 BMCLAPI，失败再试官方"
        DownloadSource.BALANCED -> lastProbeSummary
            ?: "启动后由手机测速选择；未测速时默认优先 BMCL"
    }

    fun current(): CascadeDownloadProvider {
        val forcedLib = when (source) {
            DownloadSource.OFFICIAL -> MirrorPreference.OFFICIAL_FIRST
            DownloadSource.MIRROR -> MirrorPreference.MIRROR_FIRST
            DownloadSource.BALANCED -> libraryPreference
        }
        val forcedForge = when (source) {
            DownloadSource.OFFICIAL -> MirrorPreference.OFFICIAL_FIRST
            DownloadSource.MIRROR -> MirrorPreference.MIRROR_FIRST
            DownloadSource.BALANCED -> forgePreference
        }
        // Cascade primary is only used for versionListUrl/injectUrl single-shot callers.
        val bmcl = BmclApiDownloadProvider()
        val primary = if (forcedLib == MirrorPreference.MIRROR_FIRST) bmcl else MojangDownloadProvider
        val fallback = if (forcedLib == MirrorPreference.MIRROR_FIRST) MojangDownloadProvider else bmcl
        return CascadeDownloadProvider(
            primary = primary,
            fallback = fallback,
            libraryPreference = { forcedLib },
            forgePreference = { forcedForge }
        )
    }

    private fun isMirrorUrl(url: String): Boolean =
        url.contains("bmclapi", ignoreCase = true) ||
            url.contains("bangbang93.com", ignoreCase = true) ||
            url.contains("mcimirror.top", ignoreCase = true)

    private fun label(pref: MirrorPreference): String =
        if (pref == MirrorPreference.MIRROR_FIRST) "BMCL" else "官方"

    private fun persist(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE, source.name)
            .putString(KEY_LIB_PREF, libraryPreference.name)
            .putString(KEY_FORGE_PREF, forgePreference.name)
            .putString(KEY_SUMMARY, lastProbeSummary)
            .putLong(KEY_PROBED_AT, probedAtMs)
            .apply()
    }

    private fun readPref(raw: String?, default: MirrorPreference): MirrorPreference =
        runCatching { MirrorPreference.valueOf(raw!!) }.getOrDefault(default)
}
