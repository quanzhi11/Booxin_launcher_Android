package com.booxin.launcher.core.download

import android.util.Log
import com.booxin.launcher.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class MirrorPreference {
    OFFICIAL_FIRST,
    MIRROR_FIRST
}

data class DownloadProbeResult(
    val libraryPreference: MirrorPreference,
    val forgePreference: MirrorPreference,
    val summary: String,
    val probedAtMs: Long = System.currentTimeMillis()
)

/**
 * On-device mirror probe (do not trust PC results with a system proxy).
 * Picks whichever endpoint answers first with a valid response.
 */
object DownloadSourceProbe {
    private const val TAG = "DownloadProbe"
    private const val PROBE_TIMEOUT_MS = 4_000L

    private val probeClient: OkHttpClient by lazy {
        HttpClients.shared.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun probe(): DownloadProbeResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val libOfficial = async {
                latencyMs(OFFICIAL_LIB_PROBE)
            }
            val libMirror = async {
                latencyMs(BMCL_LIB_PROBE)
            }
            val forgeOfficial = async {
                latencyMs(OFFICIAL_FORGE_PROBE)
            }
            val forgeMirror = async {
                latencyMs(BMCL_FORGE_PROBE)
            }

            val lo = libOfficial.await()
            val lm = libMirror.await()
            val fo = forgeOfficial.await()
            val fm = forgeMirror.await()

            val libraryPref = prefer(lo, lm)
            val forgePref = prefer(fo, fm)
            val summary = buildString {
                append("库: 官方${fmt(lo)} / BMCL${fmt(lm)} → ${label(libraryPref)}")
                append(" · ")
                append("Forge: 官方${fmt(fo)} / BMCL${fmt(fm)} → ${label(forgePref)}")
            }
            Log.i(TAG, summary)
            DownloadProbeResult(
                libraryPreference = libraryPref,
                forgePreference = forgePref,
                summary = summary
            )
        }
    }

    private fun prefer(officialMs: Long?, mirrorMs: Long?): MirrorPreference {
        // Default to mirror when both fail — common on CN mobile without proxy.
        if (officialMs == null && mirrorMs == null) return MirrorPreference.MIRROR_FIRST
        if (officialMs == null) return MirrorPreference.MIRROR_FIRST
        if (mirrorMs == null) return MirrorPreference.OFFICIAL_FIRST
        return if (mirrorMs <= officialMs) MirrorPreference.MIRROR_FIRST
        else MirrorPreference.OFFICIAL_FIRST
    }

    private fun latencyMs(url: String): Long? {
        val started = System.nanoTime()
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.USER_AGENT)
                .header("Range", "bytes=0-0")
                .get()
                .build()
            probeClient.newCall(request).execute().use { response ->
                // 200 or 206 are both fine; 403/404 count as unreachable for this host.
                if (!response.isSuccessful && response.code != 206) {
                    Log.w(TAG, "probe fail HTTP ${response.code}: $url")
                    return null
                }
                // Drain at most one byte so we measure real TTFB.
                response.body?.byteStream()?.read()
                ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            }
        } catch (error: Exception) {
            Log.w(TAG, "probe error $url: ${error.message}")
            null
        }
    }

    private fun fmt(ms: Long?): String = if (ms == null) "超时" else "${ms}ms"

    private fun label(pref: MirrorPreference): String =
        if (pref == MirrorPreference.MIRROR_FIRST) "选BMCL" else "选官方"

    private const val OFFICIAL_LIB_PROBE =
        "https://libraries.minecraft.net/commons-io/commons-io/2.4/commons-io-2.4.jar"
    private const val BMCL_LIB_PROBE =
        "https://bmclapi2.bangbang93.com/libraries/commons-io/commons-io/2.4/commons-io-2.4.jar"
    private const val OFFICIAL_FORGE_PROBE =
        "https://maven.minecraftforge.net/org/ow2/asm/asm/9.2/asm-9.2.jar"
    private const val BMCL_FORGE_PROBE =
        "https://bmclapi2.bangbang93.com/maven/org/ow2/asm/asm/9.2/asm-9.2.jar"
}
