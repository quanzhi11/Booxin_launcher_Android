package com.booxin.launcher.core.multiplayer

import android.util.Log
import com.booxin.launcher.BooxinApp
import com.booxin.launcher.core.net.FileDownloader
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OfficialServerInfo(
    val name: String,
    val host: String,
    val port: Int = 25565,
    val version: String = "",
    val forgeVersion: String = "",
    val modUrls: List<String> = emptyList(),
    /** Day-precision update stamp from guanfu.txt `time` field (legacy). */
    val modsUpdatedAt: LocalDate? = null
) {
    val serverAddress: String
        get() {
            val h = host.trim().filterNot { it.isWhitespace() }
            return if (port > 0) "$h:$port" else h
        }
}

/**
 * Official server config — aligned with PC [OfficialServerCatalogService]:
 * 1. Download https://www.boonix.art/guanfu.txt
 * 2. Fall back to bundled assets/official/guanfu.txt
 */
object OfficialServerCatalog {

    const val CATALOG_URL = "https://www.boonix.art/guanfu.txt"
    private const val TAG = "OfficialServer"
    private const val ASSET_FALLBACK = "official/guanfu.txt"

    private val keyValueQuoted =
        Regex("""^\s*(.+?)\s*:\s*"([^"]*)"\s*$""", RegexOption.IGNORE_CASE)
    private val keyValuePlain =
        Regex("""^\s*(.+?)\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)
    private val httpUrl = Regex("""https?://[^\s"']+""", RegexOption.IGNORE_CASE)

    private val downloader = FileDownloader()
    private val loadMutex = Mutex()

    @Volatile
    private var cached: List<OfficialServerInfo> = emptyList()

    @Volatile
    private var loadedOnce: Boolean = false

    fun getServers(): List<OfficialServerInfo> = cached

    fun invalidate() {
        cached = emptyList()
        loadedOnce = false
    }

    suspend fun ensureLoaded(force: Boolean = false): List<OfficialServerInfo> =
        withContext(Dispatchers.IO) {
            loadMutex.withLock {
                if (!force && loadedOnce) return@withLock cached
                val content = tryDownload() ?: tryLoadBundledFallback()
                cached = if (content.isNullOrBlank()) {
                    emptyList()
                } else {
                    parse(content)
                }
                loadedOnce = true
                Log.i(TAG, "loaded ${cached.size} official server(s)")
                cached
            }
        }

    private suspend fun tryDownload(): String? =
        downloader.downloadText(CATALOG_URL).fold(
            onSuccess = { text ->
                text.takeIf { it.isNotBlank() }.also {
                    if (it != null) Log.i(TAG, "downloaded $CATALOG_URL (${it.length} chars)")
                }
            },
            onFailure = { err ->
                Log.w(TAG, "download failed: ${err.message}")
                null
            }
        )

    private fun tryLoadBundledFallback(): String? =
        runCatching {
            val ctx = BooxinApp.getAppContext()
            ctx.assets.open(ASSET_FALLBACK).bufferedReader(Charsets.UTF_8).use { it.readText() }
                .takeIf { it.isNotBlank() }
                ?.also { Log.i(TAG, "using bundled $ASSET_FALLBACK") }
        }.onFailure { err ->
            Log.w(TAG, "bundled fallback missing: ${err.message}")
        }.getOrNull()

    internal fun parse(content: String): List<OfficialServerInfo> {
        var host = ""
        var port = 25565
        var name = ""
        var version = ""
        var forgeVersion = ""
        var modsUpdatedAt: LocalDate? = null
        val modUrls = mutableListOf<String>()
        var inModList = false

        for (raw in content.lineSequence()) {
            val line = stripInlineComment(raw.trim())
            if (line.isEmpty() || line.startsWith('#')) continue

            val kv = parseKeyValue(line)
            if (kv != null) {
                val (key, value) = kv
                if (isTimeKey(key)) {
                    modsUpdatedAt = parseModsUpdatedAt(value) ?: modsUpdatedAt
                    continue
                }
                if (!inModList) {
                    when {
                        key.contains("server ip", ignoreCase = true) ||
                            key.equals("server", ignoreCase = true) -> {
                            val parsed = parseHostPort(value)
                            host = parsed.first
                            port = parsed.second
                        }
                        key.contains("name", ignoreCase = true) -> name = value
                        key.contains("version", ignoreCase = true) &&
                            !key.contains("forge", ignoreCase = true) -> version = value
                        key.contains("mod_forge", ignoreCase = true) ||
                            key.contains("forge", ignoreCase = true) -> forgeVersion = value
                    }
                    continue
                }
            }

            if (line.contains("mod list", ignoreCase = true)) {
                inModList = true
                extractHttpUrls(line).forEach { addModUrl(modUrls, it) }
                continue
            }

            if (inModList) {
                extractHttpUrls(line).forEach { addModUrl(modUrls, it) }
            }
        }

        val cleanHost = host.trim().replace(Regex("\\s+"), "")
        if (cleanHost.isBlank() || name.isBlank()) return emptyList()
        return listOf(
            OfficialServerInfo(
                name = name.trim(),
                host = cleanHost,
                port = port,
                version = version.trim(),
                forgeVersion = forgeVersion.trim(),
                modUrls = modUrls,
                modsUpdatedAt = modsUpdatedAt
            )
        )
    }

    private fun stripInlineComment(line: String): String {
        var inQuotes = false
        var i = 0
        while (i < line.length - 1) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            if (!inQuotes && c == '/' && line[i + 1] == '/') {
                return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line
    }

    private fun parseKeyValue(line: String): Pair<String, String>? {
        keyValueQuoted.matchEntire(line)?.let {
            val key = it.groupValues[1].trim()
            val value = it.groupValues[2].trim()
            if (key.isNotBlank()) return key to value
        }
        keyValuePlain.matchEntire(line)?.let {
            val key = it.groupValues[1].trim()
            val value = it.groupValues[2].trim().trim('"')
            if (key.contains("mod list", ignoreCase = true)) return null
            if (key.isNotBlank() && value.isNotBlank()) return key to value
        }
        return null
    }

    private fun isTimeKey(key: String): Boolean =
        key.equals("time", ignoreCase = true) ||
            key.contains("mod_time", ignoreCase = true) ||
            key.contains("update_time", ignoreCase = true) ||
            key.contains("mod_update", ignoreCase = true)

    internal fun parseModsUpdatedAt(raw: String): LocalDate? {
        val text = raw.trim().trim('"')
        if (text.isBlank()) return null
        val parts = text.split('-', '/', '.').map { it.trim() }.filter { it.isNotEmpty() }
        return try {
            when (parts.size) {
                2 -> {
                    val month = parts[0].toInt()
                    val day = parts[1].toInt()
                    var date = MonthDay.of(month, day).atYear(LocalDate.now().year)
                    if (date.isAfter(LocalDate.now().plusDays(1))) {
                        date = date.minusYears(1)
                    }
                    date
                }
                3 -> LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                else -> runCatching {
                    LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
                }.getOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHostPort(value: String): Pair<String, Int> {
        val cleaned = value.trim().trim('"', '\'')
        val colon = cleaned.lastIndexOf(':')
        if (colon <= 0 || colon >= cleaned.length - 1) {
            return cleaned.filterNot { it.isWhitespace() } to 25565
        }
        val port = cleaned.substring(colon + 1).trim().toIntOrNull()
        val host = cleaned.substring(0, colon).trim().filterNot { it.isWhitespace() }
        return if (port != null && port > 0) {
            host to port
        } else {
            host to 25565
        }
    }

    private fun extractHttpUrls(line: String): List<String> =
        httpUrl.findAll(line).map { match ->
            match.value.trim().trimEnd('"', '\'', ',', ';')
        }.filter { it.isNotBlank() }.toList()

    private fun addModUrl(modUrls: MutableList<String>, url: String) {
        if (modUrls.none { it.equals(url, ignoreCase = true) }) {
            modUrls.add(url)
        }
    }
}
