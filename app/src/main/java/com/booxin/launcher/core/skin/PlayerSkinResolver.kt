package com.booxin.launcher.core.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import android.util.Log
import com.booxin.launcher.core.net.HttpClients
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Resolves the currently selected account's skin PNG for UI (home 3D player, etc.).
 *
 * Microsoft skins are resolved with several fallbacks because:
 * - Mojang often returns `http://textures.minecraft.net/...` (blocked by cleartext policy)
 * - `sessionserver.mojang.com` / texture CDN are frequently unreachable in CN networks
 */
object PlayerSkinResolver {

    private const val TAG = "PlayerSkinResolver"

    fun resolvePng(
        context: Context,
        account: LauncherAccount?,
        forceRefresh: Boolean = false
    ): File {
        val cacheDir = File(context.cacheDir, "home3d").also { it.mkdirs() }
        val dest = File(cacheDir, "player_skin.png")
        if (forceRefresh) {
            runCatching { dest.delete() }
        }
        val ok = runCatching {
            when {
                account == null -> false
                account.type == AccountType.OFFLINE -> copyOffline(account, dest)
                else -> downloadMicrosoftTo(account, dest)
            }
        }.onFailure {
            Log.e(TAG, "skin resolve crashed account=${account?.name}", it)
        }.getOrDefault(false)
        if (!ok || !dest.isFile || dest.length() <= 64L) {
            Log.w(
                TAG,
                "skin resolve failed; using default Steve " +
                    "(account=${account?.name} type=${account?.type} uuid=${account?.uuid} " +
                    "token=${!account?.accessToken.isNullOrBlank()})"
            )
            copyDefaultSteve(context, dest)
        } else {
            normalizeTo64x64(dest)
            Log.i(TAG, "skin ok account=${account?.name} bytes=${dest.length()}")
        }
        return dest
    }

    fun clearCache(context: Context) {
        runCatching {
            File(context.cacheDir, "home3d/player_skin.png").delete()
        }
    }

    /**
     * Per-account skin cache for account-list avatars (does not overwrite home3d cache).
     * Microsoft accounts download via the same fallbacks as [resolvePng]; offline uses local PNG.
     */
    fun resolveAccountSkinPng(
        context: Context,
        account: LauncherAccount,
        forceRefresh: Boolean = false
    ): File {
        val cacheDir = File(context.cacheDir, "account_skins").also { it.mkdirs() }
        val safeId = account.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dest = File(cacheDir, "$safeId.png")
        if (forceRefresh) {
            runCatching { dest.delete() }
        } else if (dest.isFile && dest.length() > 64L) {
            return dest
        }
        val ok = runCatching {
            when (account.type) {
                AccountType.OFFLINE -> copyOffline(account, dest)
                AccountType.MICROSOFT -> downloadMicrosoftTo(account, dest)
            }
        }.onFailure {
            Log.e(TAG, "account skin resolve crashed id=${account.id} name=${account.name}", it)
        }.getOrDefault(false)
        if (!ok || !dest.isFile || dest.length() <= 64L) {
            copyDefaultSteve(context, dest)
        } else {
            normalizeTo64x64(dest)
        }
        return dest
    }

    fun clearAccountSkinCache(context: Context, accountId: String? = null) {
        val dir = File(context.cacheDir, "account_skins")
        if (accountId.isNullOrBlank()) {
            runCatching { dir.deleteRecursively() }
            return
        }
        val safeId = accountId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        runCatching { File(dir, "$safeId.png").delete() }
    }

    private fun copyOffline(account: LauncherAccount, dest: File): Boolean {
        val path = account.skinPath?.takeIf { it.isNotBlank() }?.let(::File)
        val src = when {
            path != null && path.isFile -> path
            OfflineSkinStore.skinFile(account.id).isFile -> OfflineSkinStore.skinFile(account.id)
            else -> return false
        }
        src.copyTo(dest, overwrite = true)
        return dest.isFile && dest.length() > 64L
    }

    private fun downloadMicrosoftTo(account: LauncherAccount, dest: File): Boolean {
        val uuid = account.uuid?.replace("-", "")?.trim()?.lowercase().orEmpty()
        val name = account.name.trim()
        Log.i(TAG, "resolve MS skin name=$name uuidLen=${uuid.length} hasToken=${!account.accessToken.isNullOrBlank()}")

        // 1) Official profile with MC access token.
        val token = account.accessToken?.takeIf { it.isNotBlank() }
        if (token != null) {
            skinUrlFromMcServices(token)?.let { url ->
                if (downloadPngCandidates(dest, textureUrlCandidates(url))) {
                    Log.i(TAG, "skin via minecraftservices for $name")
                    return true
                }
                Log.w(TAG, "minecraftservices url found but download failed: $url")
            }
        } else {
            Log.w(TAG, "no MC accessToken for $name")
        }

        // 2) Session profile (official + BMCL Yggdrasil mirror) → texture URL.
        if (uuid.length == 32) {
            skinUrlFromSessionProfile(uuid)?.let { url ->
                if (downloadPngCandidates(dest, textureUrlCandidates(url))) {
                    Log.i(TAG, "skin via session profile for $name")
                    return true
                }
                Log.w(TAG, "session profile url found but download failed: $url")
            }
        }

        // 3) Direct PNG mirrors by UUID / name (CN-friendly first).
        val mirrorUrls = buildList {
            if (uuid.length == 32) {
                add("https://bmclapi2.bangbang93.com/skin/$uuid")
                add("https://crafthead.net/skin/$uuid")
                add("https://crafatar.com/skins/$uuid")
                add("https://mc-heads.net/skin/$uuid")
                add("https://minotar.net/skin/$uuid")
            }
            if (name.isNotBlank()) {
                val enc = java.net.URLEncoder.encode(name, "UTF-8")
                add("https://bmclapi2.bangbang93.com/skin/$enc")
                add("https://crafthead.net/skin/$enc")
                add("https://mc-heads.net/skin/$enc")
                add("https://minotar.net/skin/$enc")
            }
        }
        if (downloadPngCandidates(dest, mirrorUrls)) {
            Log.i(TAG, "skin via mirror for $name")
            return true
        }
        return false
    }

    private fun skinUrlFromMcServices(mcToken: String): String? {
        val req = Request.Builder()
            .url("https://api.minecraftservices.com/minecraft/profile")
            .header("Authorization", "Bearer $mcToken")
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return runCatching {
            HttpClients.cascadeAttempt.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "minecraftservices profile HTTP ${resp.code}")
                    return null
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val skins = json.optJSONArray("skins") ?: return null
                var anyUrl: String? = null
                for (i in 0 until skins.length()) {
                    val skin = skins.optJSONObject(i) ?: continue
                    val url = skin.optString("url").trim()
                    if (url.isBlank()) continue
                    if (anyUrl == null) anyUrl = url
                    if (skin.optString("state").equals("ACTIVE", ignoreCase = true)) {
                        return url
                    }
                }
                anyUrl
            }
        }.onFailure { Log.w(TAG, "minecraftservices profile failed", it) }.getOrNull()
    }

    private fun skinUrlFromSessionProfile(uuidNoDash: String): String? {
        val endpoints = listOf(
            "https://bmclapi2.bangbang93.com/yggdrasil/sessionserver/session/minecraft/profile/$uuidNoDash",
            "https://sessionserver.mojang.com/session/minecraft/profile/$uuidNoDash"
        )
        for (endpoint in endpoints) {
            val url = runCatching { fetchSkinUrlFromProfileJson(endpoint) }
                .onFailure { Log.w(TAG, "session profile failed: $endpoint", it) }
                .getOrNull()
            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    private fun fetchSkinUrlFromProfileJson(profileUrl: String): String? {
        val req = Request.Builder()
            .url(profileUrl)
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return HttpClients.cascadeAttempt.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "profile HTTP ${resp.code}: $profileUrl")
                return null
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            val texturesB64 = JSONObject(body)
                .optJSONArray("properties")
                ?.let { arr ->
                    (0 until arr.length()).asSequence()
                        .map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("name") == "textures" }
                        ?.optString("value")
                }
                .orEmpty()
            if (texturesB64.isBlank()) return null
            val texturesJson = String(Base64.decode(texturesB64, Base64.DEFAULT))
            JSONObject(texturesJson)
                .optJSONObject("textures")
                ?.optJSONObject("SKIN")
                ?.optString("url")
                ?.takeIf { it.isNotBlank() }
        }
    }

    /** Prefer CN mirror texture CDN before Mojang. */
    private fun textureUrlCandidates(rawUrl: String): List<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return emptyList()
        val https = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) ->
                "https://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
            else -> trimmed
        }
        val out = linkedSetOf<String>()
        val hash = Regex("""/texture/([0-9a-fA-F]+)""")
            .find(https)
            ?.groupValues
            ?.getOrNull(1)
        if (!hash.isNullOrBlank()) {
            out += "https://bmclapi2.bangbang93.com/textures/$hash"
            out += "https://textures.minecraft.net/texture/$hash"
        }
        out += https
        if (!https.equals(trimmed, ignoreCase = true)) out += trimmed
        return out.toList()
    }

    private fun downloadPngCandidates(dest: File, urls: List<String>): Boolean {
        for (url in urls) {
            if (url.isBlank()) continue
            if (downloadPng(url, dest)) return true
        }
        return false
    }

    private fun downloadPng(url: String, dest: File): Boolean {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Accept", "image/png,image/*,*/*")
            .get()
            .build()
        return runCatching {
            HttpClients.cascadeAttempt.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "skin download HTTP ${resp.code}: $url")
                    return false
                }
                val bytes = resp.body?.bytes() ?: return false
                if (bytes.size < 64 || bytes[0] != 0x89.toByte() || bytes[1] != 0x50.toByte()) {
                    Log.w(TAG, "skin download not PNG (${bytes.size} bytes): $url")
                    return false
                }
                FileOutputStream(dest).use { it.write(bytes) }
                dest.isFile && dest.length() > 64L
            }
        }.onFailure { Log.w(TAG, "skin download failed: $url", it) }.getOrDefault(false)
    }

    private fun normalizeTo64x64(file: File) {
        runCatching {
            val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return
            if (raw.width == 64 && raw.height == 64) {
                raw.recycle()
                return
            }
            val out = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            if (raw.width == 64 && raw.height == 32) {
                canvas.drawBitmap(raw, 0f, 0f, null)
                val leg = Bitmap.createBitmap(raw, 0, 16, 16, 16)
                canvas.drawBitmap(leg, 16f, 48f, null)
                leg.recycle()
                val arm = Bitmap.createBitmap(raw, 40, 16, 16, 16)
                canvas.drawBitmap(arm, 32f, 48f, null)
                arm.recycle()
            } else {
                val scaled = Bitmap.createScaledBitmap(raw, 64, 64, false)
                canvas.drawBitmap(scaled, 0f, 0f, null)
                if (scaled !== raw) scaled.recycle()
            }
            raw.recycle()
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.PNG, 100, bos)
            out.recycle()
            FileOutputStream(file).use { it.write(bos.toByteArray()) }
        }
    }

    private fun copyDefaultSteve(context: Context, dest: File) {
        runCatching {
            context.assets.open("home3d/default_steve.png").use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        }
    }
}
