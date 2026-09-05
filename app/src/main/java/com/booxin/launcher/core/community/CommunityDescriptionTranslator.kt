package com.booxin.launcher.core.community

import android.content.Context
import android.util.Log
import android.util.LruCache
import android.widget.TextView
import com.booxin.launcher.R
import com.booxin.launcher.core.net.HttpClients
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Auto-translate Modrinth project blurbs / bodies into Simplified Chinese,
 * and Chinese community search queries into English for Modrinth.
 *
 * Translation runs on an app-level scope so RecyclerView recycle / fragment
 * lifecycle cancel does not abort in-flight work (results still fill the cache).
 */
object CommunityDescriptionTranslator {
    private const val TAG = "CommunityTranslate"
    private const val MAX_CHUNK = 450
    private const val PREFS_ZH = "community_desc_zh"
    private const val PREFS_EN = "community_query_en"

    private val memory = LruCache<String, String>(512)
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val gate = Semaphore(permits = 3)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context

    private enum class Target(val prefs: String, val cachePrefix: String) {
        ZH(PREFS_ZH, "zh:"),
        EN(PREFS_EN, "en:")
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun bind(
        textView: TextView,
        source: String,
        scope: CoroutineScope,
        transformingHint: Boolean = true
    ) {
        val original = source.trim()
        textView.setTag(R.id.tag_community_translate_src, original)
        (textView.getTag(R.id.tag_community_translate_job) as? Job)?.cancel()

        if (original.isBlank()) {
            textView.text = textView.context.getString(R.string.community_no_description)
            return
        }

        cached(original, Target.ZH)?.let { hit ->
            textView.text = hit
            return
        }

        textView.text = if (transformingHint && needsTranslate(original)) {
            textView.context.getString(R.string.community_translating_preview, original)
        } else {
            original
        }

        if (!needsTranslate(original)) return

        val job = scope.launch {
            val translated = translate(original)
            if (textView.getTag(R.id.tag_community_translate_src) != original) return@launch
            textView.text = translated
        }
        textView.setTag(R.id.tag_community_translate_job, job)
    }

    /** Warm cache for a page of search results (fire-and-forget). */
    fun prefetch(texts: Collection<String>) {
        texts.forEach { raw ->
            val text = raw.trim()
            if (text.isBlank() || !needsTranslate(text) || cached(text, Target.ZH) != null) return@forEach
            appScope.launch { runCatching { translate(text) } }
        }
    }

    suspend fun translate(text: String): String = translateInternal(text, Target.ZH)

    /** Chinese / mixed query → English for Modrinth search. */
    suspend fun translateToEnglish(text: String): String = translateInternal(text, Target.EN)

    private suspend fun translateInternal(text: String, target: Target): String =
        withContext(Dispatchers.IO) {
            val trimmed = text.trim()
            if (trimmed.isBlank()) return@withContext trimmed
            if (target == Target.ZH && !needsTranslate(trimmed)) return@withContext trimmed
            if (target == Target.EN && !CommunitySearchQuery.containsChinese(trimmed)) {
                return@withContext trimmed
            }
            cached(trimmed, target)?.let { return@withContext it }

            val key = keyOf(trimmed, target)
            val existing = inflight[key]
            if (existing != null) return@withContext existing.await()

            val deferred = CompletableDeferred<String>()
            val winner = inflight.putIfAbsent(key, deferred)
            if (winner != null) return@withContext winner.await()

            try {
                val chunks = chunk(trimmed)
                val out = StringBuilder()
                for (part in chunks) {
                    val done = gate.withPermit {
                        translateChunk(part, target).getOrElse { err ->
                            Log.w(TAG, "chunk failed (${target.name}): ${err.message}")
                            part
                        }
                    }
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(done)
                }
                val result = out.toString().ifBlank { trimmed }
                if (result != trimmed) {
                    putCache(trimmed, result, target)
                } else {
                    Log.w(TAG, "all providers returned original (${trimmed.length} chars) → ${target.name}")
                }
                deferred.complete(result)
                result
            } catch (t: Throwable) {
                deferred.complete(trimmed)
                Log.w(TAG, "translate failed (${target.name})", t)
                trimmed
            } finally {
                inflight.remove(key, deferred)
            }
        }

    fun needsTranslate(text: String): Boolean {
        val sample = text.take(800)
        var letters = 0
        var cjk = 0
        var latin = 0
        for (ch in sample) {
            when {
                ch in '\u4e00'..'\u9fff' || ch in '\u3400'..'\u4dbf' -> {
                    letters++
                    cjk++
                }
                ch.isLetter() -> {
                    letters++
                    if (ch in 'A'..'Z' || ch in 'a'..'z') latin++
                }
            }
        }
        if (latin < 4 && letters < 6) return false
        if (letters == 0) return latin >= 4
        return cjk.toFloat() / letters < 0.40f
    }

    private fun cached(source: String, target: Target): String? {
        val key = keyOf(source, target)
        memory.get(key)?.let { return it }
        if (!::appContext.isInitialized) return null
        val prefs = appContext.getSharedPreferences(target.prefs, Context.MODE_PRIVATE)
        return prefs.getString(key, null)?.also { memory.put(key, it) }
    }

    private fun putCache(source: String, translated: String, target: Target) {
        val key = keyOf(source, target)
        memory.put(key, translated)
        if (!::appContext.isInitialized) return
        appContext.getSharedPreferences(target.prefs, Context.MODE_PRIVATE)
            .edit()
            .putString(key, translated)
            .apply()
    }

    private fun keyOf(source: String, target: Target = Target.ZH): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((target.cachePrefix + source).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun chunk(text: String): List<String> {
        if (text.length <= MAX_CHUNK) return listOf(text)
        val parts = mutableListOf<String>()
        val paragraphs = text.split(Regex("\\n{2,}"))
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                parts += buf.toString()
                buf.clear()
            }
        }
        for (p in paragraphs) {
            if (p.length > MAX_CHUNK) {
                flush()
                var i = 0
                while (i < p.length) {
                    parts += p.substring(i, minOf(i + MAX_CHUNK, p.length))
                    i += MAX_CHUNK
                }
            } else if (buf.length + p.length + 2 > MAX_CHUNK) {
                flush()
                buf.append(p)
            } else {
                if (buf.isNotEmpty()) buf.append("\n\n")
                buf.append(p)
            }
        }
        flush()
        return parts.ifEmpty { listOf(text.take(MAX_CHUNK)) }
    }

    private fun translateChunk(text: String, target: Target): Result<String> {
        val providers = when (target) {
            Target.ZH -> listOf(
                { translateYoudaoDemo(text, toZh = true) },
                { translateMyMemory(text, toZh = true) },
                { translateGoogleGtx(text, toZh = true) }
            )
            Target.EN -> listOf(
                { translateYoudaoDemo(text, toZh = false) },
                { translateMyMemory(text, toZh = false) },
                { translateGoogleGtx(text, toZh = false) }
            )
        }
        var last: Throwable? = null
        for (provider in providers) {
            val result = runCatching { provider() }
            val value = result.getOrNull()?.trim()
            if (!value.isNullOrBlank() && !value.equals(text, ignoreCase = true)) {
                return Result.success(value)
            }
            last = result.exceptionOrNull() ?: IllegalStateException("unchanged")
        }
        return Result.failure(last ?: IllegalStateException("translate failed"))
    }

    private fun client() = HttpClients.shared

    private fun translateYoudaoDemo(text: String, toZh: Boolean): String {
        val body = FormBody.Builder()
            .add("q", text)
            .add("from", "Auto")
            .add("to", if (toZh) "zh-CHS" else "en")
            .build()
        val req = Request.Builder()
            .url("https://aidemo.youdao.com/trans")
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Referer", "https://ai.youdao.com/")
            .post(body)
            .build()
        client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("youdao http ${resp.code}")
            val root = JSONObject(resp.body?.string().orEmpty())
            if (root.optString("errorCode", "0") != "0") {
                error("youdao error ${root.optString("errorCode")}")
            }
            val arr = root.optJSONArray("translation") ?: error("youdao no translation")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                if (i > 0) sb.append('\n')
                sb.append(arr.optString(i, ""))
            }
            return sb.toString().trim().ifBlank { error("youdao blank") }
        }
    }

    private fun translateMyMemory(text: String, toZh: Boolean): String {
        val q = URLEncoder.encode(text, Charsets.UTF_8.name())
        val pair = if (toZh) "en|zh-CN" else "zh-CN|en"
        val url = "https://api.mymemory.translated.net/get?q=$q&langpair=$pair"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.USER_AGENT)
            .get()
            .build()
        client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("mymemory http ${resp.code}")
            val root = JSONObject(resp.body?.string().orEmpty())
            val translated = root.optJSONObject("responseData")
                ?.optString("translatedText")
                .orEmpty()
                .trim()
            if (translated.isBlank() || translated.contains("MYMEMORY WARNING", ignoreCase = true)) {
                error("mymemory empty/quota")
            }
            return translated
        }
    }

    private fun translateGoogleGtx(text: String, toZh: Boolean): String {
        val q = URLEncoder.encode(text, Charsets.UTF_8.name())
        val tl = if (toZh) "zh-CN" else "en"
        val url =
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$tl&dt=t&q=$q"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.USER_AGENT)
            .get()
            .build()
        client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("gtx http ${resp.code}")
            val root = JSONArray(resp.body?.string().orEmpty())
            val sentences = root.optJSONArray(0) ?: error("gtx empty")
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val row = sentences.optJSONArray(i) ?: continue
                sb.append(row.optString(0, ""))
            }
            return sb.toString().trim().ifBlank { error("gtx blank") }
        }
    }
}
