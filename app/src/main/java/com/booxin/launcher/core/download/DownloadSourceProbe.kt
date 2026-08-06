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
import kotlin.math.max

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
 *
 * Scores each endpoint by real download throughput of a small sample,
 * 不只看 TTFB，CDN 可能先快后限速。
 */
object DownloadSourceProbe {
    private const val TAG = "DownloadProbe"
    private const val PROBE_TIMEOUT_MS = 5_000L
    /** Bytes to pull for a throughput sample (cap). */
    private const val SAMPLE_BYTES = 64 * 1024
    /**
     * 镜像与官方差距在此内则优先镜像。
     * Ties / near-ties → BMCL (CN mobile default).
     */
    private const val HYSTERESIS_RATIO = 0.15
    private const val HYSTERESIS_MS = 120L

    private val probeClient: OkHttpClient by lazy {
        HttpClients.shared.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS + 800, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun probe(): DownloadProbeResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val libOfficial = async { score(OFFICIAL_LIB_PROBE) }
            val libMirror = async { score(BMCL_LIB_PROBE) }
            val forgeOfficial = async { score(OFFICIAL_FORGE_PROBE) }
            val forgeMirror = async { score(BMCL_FORGE_PROBE) }

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

    /**
     * Lower score is better (effective ms to fetch [SAMPLE_BYTES], scaled).
     * null = unreachable.
     */
    private fun prefer(official: Long?, mirror: Long?): MirrorPreference {
        if (official == null && mirror == null) return MirrorPreference.MIRROR_FIRST
        if (official == null) return MirrorPreference.MIRROR_FIRST
        if (mirror == null) return MirrorPreference.OFFICIAL_FIRST
        // Near-tie → mirror (safer on CN cellular / campus nets).
        val margin = max((official * HYSTERESIS_RATIO).toLong(), HYSTERESIS_MS)
        return if (mirror <= official + margin) MirrorPreference.MIRROR_FIRST
        else MirrorPreference.OFFICIAL_FIRST
    }

    private fun score(url: String): Long? {
        val started = System.nanoTime()
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.USER_AGENT)
                .header("Range", "bytes=0-${SAMPLE_BYTES - 1}")
                .get()
                .build()
            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    Log.w(TAG, "probe fail HTTP ${response.code}: $url")
                    return null
                }
                val stream = response.body?.byteStream() ?: return null
                val buf = ByteArray(8 * 1024)
                var readTotal = 0
                while (readTotal < SAMPLE_BYTES) {
                    val n = stream.read(buf, 0, minOf(buf.size, SAMPLE_BYTES - readTotal))
                    if (n < 0) break
                    readTotal += n
                }
                if (readTotal <= 0) {
                    Log.w(TAG, "probe empty body: $url")
                    return null
                }
                val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                // Normalize to "ms for SAMPLE_BYTES" so partial reads stay comparable.
                val normalized = elapsedMs * SAMPLE_BYTES / readTotal.coerceAtLeast(1)
                Log.d(TAG, "probe ok $url read=$readTotal in ${elapsedMs}ms → score=$normalized")
                normalized
            }
        } catch (error: Exception) {
            Log.w(TAG, "probe error $url: ${error.message}")
            null
        }
    }

    private fun fmt(score: Long?): String = if (score == null) "超时" else "${score}ms"

    private fun label(pref: MirrorPreference): String =
        if (pref == MirrorPreference.MIRROR_FIRST) "选BMCL" else "选官方"

    private const val OFFICIAL_LIB_PROBE =
        "https://libraries.minecraft.net/commons-io/commons-io/2.15.1/commons-io-2.15.1.jar"
    private const val BMCL_LIB_PROBE =
        "https://bmclapi2.bangbang93.com/libraries/commons-io/commons-io/2.15.1/commons-io-2.15.1.jar"
    private const val OFFICIAL_FORGE_PROBE =
        "https://maven.minecraftforge.net/org/ow2/asm/asm/9.6/asm-9.6.jar"
    private const val BMCL_FORGE_PROBE =
        "https://bmclapi2.bangbang93.com/maven/org/ow2/asm/asm/9.6/asm-9.6.jar"
}
