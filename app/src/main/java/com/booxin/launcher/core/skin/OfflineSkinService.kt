package com.booxin.launcher.core.skin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.booxin.launcher.core.java.MinecraftJavaRequirement
import com.booxin.launcher.core.net.HttpClients
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline skin selection, UUID rules, Mojang player fetch, and launch-time resource pack.
 * Aligned with PC `OfflineSkinService`.
 */
object OfflineSkinService {
    private const val TAG = "OfflineSkin"
    const val PACK_FILE_NAME = "Booxin Offline Skin.zip"
    const val MODERN_PACK_ID = "file/$PACK_FILE_NAME"
    const val LEGACY_PACK_ID = PACK_FILE_NAME

    private val PLAYER_NAME = Regex("^[0-9A-Za-z_]{3,16}$")
    private val DEFAULT_SKIN_NAMES = arrayOf(
        "alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"
    )

    data class PlayerProfile(
        val playerName: String,
        val uuid: String,
        val model: String,
        val skinBytes: ByteArray
    )

    data class DefaultSkinSelection(val name: String, val slim: Boolean)

    data class Validation(val isValid: Boolean, val message: String, val width: Int = 0, val height: Int = 0)

    data class LaunchPrep(val applied: Boolean, val message: String, val packPath: String? = null)

    fun effectiveMode(account: LauncherAccount): OfflineSkinMode {
        val parsed = OfflineSkinMode.parse(account.skinMode)
        if (parsed != OfflineSkinMode.RANDOM) return parsed
        // Legacy: imported PNG without mode → treat as custom.
        if (!account.skinPath.isNullOrBlank() && File(account.skinPath).isFile) {
            return OfflineSkinMode.CUSTOM
        }
        return OfflineSkinMode.RANDOM
    }

    fun isSlimModel(model: String?): Boolean =
        model.equals("slim", ignoreCase = true)

    fun validateSkinBytes(bytes: ByteArray?): Validation {
        if (bytes == null || bytes.size < 8) {
            return Validation(false, "文件为空或不是有效的皮肤图片")
        }
        return try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inScaled = false
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val width = opts.outWidth
            val height = opts.outHeight
            if (width <= 0 || height <= 0) {
                return Validation(false, "皮肤图片解码失败")
            }
            val ok = width >= 64 && width % 64 == 0 && (height == width || height * 2 == width)
            if (!ok) {
                Validation(false, "尺寸应为 64×64、64×32 或等比例高清倍数", width, height)
            } else {
                Validation(true, "有效皮肤（${width}×${height}）", width, height)
            }
        } catch (t: Throwable) {
            Validation(false, "皮肤图片解码失败：${t.message}")
        }
    }

    fun validateSkinFile(path: String?): Validation {
        if (path.isNullOrBlank() || !File(path).isFile) {
            return Validation(false, "请选择存在的 Minecraft 皮肤图片")
        }
        return validateSkinBytes(runCatching { File(path).readBytes() }.getOrNull())
    }

    fun normalizeToPng(bytes: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("无法解析图片")
        return try {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    suspend fun resolvePlayerSkin(playerName: String): PlayerProfile = withContext(Dispatchers.IO) {
        val name = playerName.trim()
        require(PLAYER_NAME.matches(name)) {
            "正版玩家名应为 3–16 位字母、数字或下划线"
        }
        val profileUrl =
            "https://api.mojang.com/users/profiles/minecraft/${java.net.URLEncoder.encode(name, "UTF-8")}"
        val profileBody = httpGet(profileUrl)
            ?: error("没有找到这个正版玩家，请检查玩家名")
        val profile = JSONObject(profileBody)
        val uuid = profile.optString("id").replace("-", "").trim().lowercase(Locale.US)
        val resolvedName = profile.optString("name").ifBlank { name }
        require(isUuid(uuid)) { "Mojang 返回的玩家 UUID 无效" }

        val sessionUrl = "https://sessionserver.mojang.com/session/minecraft/profile/$uuid"
        val sessionBody = httpGet(sessionUrl)
            ?: error("获取玩家皮肤档案失败")
        val session = JSONObject(sessionBody)
        val props = session.optJSONArray("properties") ?: JSONArray()
        var encoded: String? = null
        for (i in 0 until props.length()) {
            val p = props.optJSONObject(i) ?: continue
            if (p.optString("name").equals("textures", ignoreCase = true)) {
                encoded = p.optString("value")
                break
            }
        }
        require(!encoded.isNullOrBlank()) { "这个玩家没有可用的皮肤纹理" }
        val texturesJson = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT)))
        val skin = texturesJson.optJSONObject("textures")?.optJSONObject("SKIN")
            ?: error("这个玩家当前没有可下载的皮肤")
        val skinUrl = skin.optString("url")
        require(skinUrl.isNotBlank()) { "这个玩家当前没有可下载的皮肤" }
        val model = if (
            skin.optJSONObject("metadata")?.optString("model")
                .equals("slim", ignoreCase = true)
        ) {
            "slim"
        } else {
            "classic"
        }
        // Mojang profile often embeds http://textures.minecraft.net/... which Android blocks.
        val skinBytes = httpGetBytesCandidates(textureUrlCandidates(skinUrl))
            ?: error("下载皮肤失败")
        val validation = validateSkinBytes(skinBytes)
        require(validation.isValid) { "下载到的玩家皮肤无效：${validation.message}" }
        PlayerProfile(resolvedName, uuid, model, normalizeToPng(skinBytes))
    }

    /** Prefer HTTPS + CN mirror before Mojang cleartext CDN. */
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
        return out.toList()
    }

    private fun httpGet(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.USER_AGENT)
            .get()
            .build()
        HttpClients.shared.newCall(req).execute().use { resp ->
            if (resp.code == 204 || resp.code == 404) return null
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string()?.takeIf { it.isNotBlank() }
        }
    }

    private fun httpGetBytesCandidates(urls: List<String>): ByteArray? {
        var lastError: Throwable? = null
        for (url in urls) {
            if (url.isBlank()) continue
            runCatching { httpGetBytes(url) }
                .onSuccess { bytes -> if (bytes != null) return bytes }
                .onFailure { lastError = it }
        }
        if (lastError != null) throw lastError!!
        return null
    }

    private fun httpGetBytes(url: String): ByteArray? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.USER_AGENT)
            .get()
            .build()
        HttpClients.shared.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.bytes()?.takeIf { it.isNotEmpty() }
        }
    }

    fun resolveLaunchUuid(
        account: LauncherAccount,
        minecraftVersion: String?
    ): String {
        val base = account.uuid?.replace("-", "")?.ifBlank { null }
            ?: com.booxin.launcher.core.launch.OfflineAuth.uuidNoDash(account.name)
        if (account.type != AccountType.OFFLINE) return base
        val mode = effectiveMode(account)
        val slim = isSlimModel(account.skinModel)
        return when (mode) {
            OfflineSkinMode.STEVE ->
                if (usesModernDefaultSkins(minecraftVersion)) {
                    forceDefaultSkinVariant(base, "steve", slim = false, minecraftVersion)
                } else {
                    forceSkinModel(base, slim = false)
                }
            OfflineSkinMode.ALEX ->
                if (usesModernDefaultSkins(minecraftVersion)) {
                    forceDefaultSkinVariant(base, "alex", slim = true, minecraftVersion)
                } else {
                    forceSkinModel(base, slim = true)
                }
            OfflineSkinMode.PLAYER -> {
                val playerUuid = account.skinPlayerUuid?.replace("-", "")?.lowercase(Locale.US)
                if (isUuid(playerUuid) && !isPlayerSkinUuidUnsupported(minecraftVersion)) {
                    playerUuid!!
                } else {
                    base
                }
            }
            OfflineSkinMode.CUSTOM ->
                if (!minecraftVersion.isNullOrBlank() && usesModernDefaultSkins(minecraftVersion)) {
                    forceDefaultSkinModel(base, slim, minecraftVersion)
                } else {
                    forceSkinModel(base, slim)
                }
            OfflineSkinMode.RANDOM -> base
        }
    }

    fun selectedSkinPath(account: LauncherAccount): String? {
        return when (effectiveMode(account)) {
            OfflineSkinMode.PLAYER, OfflineSkinMode.CUSTOM ->
                account.skinPath?.takeIf { File(it).isFile }
                    ?: OfflineSkinStore.skinFile(account.id).takeIf { it.isFile }?.absolutePath
            else -> null
        }
    }

    fun prepareForLaunch(
        gameDirectory: File,
        minecraftVersion: String?,
        account: LauncherAccount
    ): LaunchPrep {
        val resourcePackDir = File(gameDirectory, "resourcepacks")
        val packPath = File(resourcePackDir, PACK_FILE_NAME)
        val optionsPath = File(gameDirectory, "options.txt")
        val mode = effectiveMode(account)
        val skinPath = selectedSkinPath(account)
        val shouldApply = account.type == AccountType.OFFLINE &&
            mode in listOf(OfflineSkinMode.PLAYER, OfflineSkinMode.CUSTOM) &&
            !skinPath.isNullOrBlank() &&
            File(skinPath).isFile
        val label = when (mode) {
            OfflineSkinMode.PLAYER -> "正版玩家离线皮肤"
            OfflineSkinMode.CUSTOM -> "本地自定义离线皮肤"
            else -> "离线皮肤"
        }
        return try {
            resourcePackDir.mkdirs()
            if (!shouldApply) {
                if (packPath.isFile) packPath.delete()
                updateOptionsResourcePack(optionsPath, minecraftVersion, enabled = false)
                return LaunchPrep(false, "未启用$label；已清理启动器皮肤包", packPath.absolutePath)
            }
            val packFormat = getPackFormat(minecraftVersion)
            if (packFormat <= 0) {
                if (packPath.isFile) packPath.delete()
                updateOptionsResourcePack(optionsPath, minecraftVersion, enabled = false)
                return LaunchPrep(false, "该 Minecraft 版本不支持资源包，已跳过$label", packPath.absolutePath)
            }
            val slim = isSlimModel(account.skinModel)
            buildSkinResourcePack(
                packPath = packPath,
                skinFile = File(skinPath!!),
                minecraftVersion = minecraftVersion,
                packFormat = packFormat,
                slim = slim,
                applyToBothModernModels = mode == OfflineSkinMode.PLAYER &&
                    usesModernDefaultSkins(minecraftVersion)
            )
            updateOptionsResourcePack(optionsPath, minecraftVersion, enabled = true)
            LaunchPrep(true, "${label}资源包已准备（pack_format=$packFormat）", packPath.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "prepareForLaunch failed", t)
            LaunchPrep(false, "离线皮肤资源包准备失败：${t.message}", packPath.absolutePath)
        }
    }

    fun resolveDefaultSkin(uuid: String?, minecraftVersion: String?): DefaultSkinSelection {
        val normalized = normalizeUuid(uuid)
        if (!usesModernDefaultSkins(minecraftVersion) ||
            normalized.length != 32 ||
            !normalized.all { it.isDigit() || it in 'a'..'f' }
        ) {
            return if (isSlimUuid(normalized)) {
                DefaultSkinSelection("alex", true)
            } else {
                DefaultSkinSelection("steve", false)
            }
        }
        val hash = computeJavaUuidHashCode(normalized)
        val index = ((hash.toLong() % 18 + 18) % 18).toInt()
        val slim = index < 9
        return DefaultSkinSelection(DEFAULT_SKIN_NAMES[index % 9], slim)
    }

    fun isSlimUuid(uuid: String?): Boolean {
        val n = normalizeUuid(uuid)
        if (n.length != 32) return false
        val parity = hexValue(n[7]) xor hexValue(n[15]) xor hexValue(n[23]) xor hexValue(n[31])
        return parity and 1 == 1
    }

    fun forceSkinModel(uuid: String, slim: Boolean): String {
        val normalized = normalizeUuid(uuid)
        if (!isUuid(normalized) || isSlimUuid(normalized) == slim) {
            return if (normalized.length == 32) normalized else uuid
        }
        val prefix = normalized.substring(0, 27)
        val suffix = normalized.substring(27).toInt(16)
        for (offset in 1..0xFFFFF) {
            val candidate = prefix + ((suffix + offset) and 0xFFFFF).toString(16).padStart(5, '0')
            if (isSlimUuid(candidate) == slim) return candidate
        }
        return normalized
    }

    private fun forceDefaultSkinVariant(
        uuid: String,
        skinName: String,
        slim: Boolean,
        minecraftVersion: String?
    ): String {
        val normalized = normalizeUuid(uuid)
        if (!isUuid(normalized)) return uuid
        val current = resolveDefaultSkin(normalized, minecraftVersion)
        if (current.name.equals(skinName, ignoreCase = true) && current.slim == slim) {
            return normalized
        }
        val prefix = normalized.substring(0, 27)
        val suffix = normalized.substring(27).toInt(16)
        for (offset in 1..0xFFFFF) {
            val candidate = prefix + ((suffix + offset) and 0xFFFFF).toString(16).padStart(5, '0')
            val selection = resolveDefaultSkin(candidate, minecraftVersion)
            if (selection.slim == slim && selection.name.equals(skinName, ignoreCase = true)) {
                return candidate
            }
        }
        return forceSkinModel(normalized, slim)
    }

    private fun forceDefaultSkinModel(
        uuid: String,
        slim: Boolean,
        minecraftVersion: String?
    ): String {
        val normalized = normalizeUuid(uuid)
        if (!isUuid(normalized)) return uuid
        if (resolveDefaultSkin(normalized, minecraftVersion).slim == slim) return normalized
        val prefix = normalized.substring(0, 27)
        val suffix = normalized.substring(27).toInt(16)
        for (offset in 1..0xFFFFF) {
            val candidate = prefix + ((suffix + offset) and 0xFFFFF).toString(16).padStart(5, '0')
            if (resolveDefaultSkin(candidate, minecraftVersion).slim == slim) return candidate
        }
        return forceSkinModel(normalized, slim)
    }

    private fun isPlayerSkinUuidUnsupported(minecraftVersion: String?): Boolean {
        if (minecraftVersion.isNullOrBlank()) return false
        val (minor, _) = versionParts(minecraftVersion)
        // 1.20+: do not impersonate official UUID (PCL/PC).
        return minor < 0 || minor >= 20
    }

    private fun usesModernDefaultSkins(minecraftVersion: String?): Boolean {
        val (minor, patch) = versionParts(minecraftVersion)
        return minor < 0 || minor > 19 || (minor == 19 && patch >= 3)
    }

    fun getPackFormat(minecraftVersion: String?): Int {
        yearBased(minecraftVersion)?.let { (year, yearMinor, suffix) ->
            if (year == 26) {
                return when {
                    yearMinor >= 2 -> 88
                    yearMinor == 1 ->
                        if (suffix.contains("snapshot", ignoreCase = true)) 76 else 84
                    else -> 75
                }
            }
        }
        val (minor, patch) = versionParts(minecraftVersion)
        return when {
            minor < 0 -> 75
            minor <= 5 -> 0
            minor <= 8 -> 1
            minor <= 10 -> 2
            minor <= 12 -> 3
            minor <= 14 -> 4
            minor == 15 -> 5
            minor == 16 -> if (patch <= 1) 5 else 6
            minor == 17 -> 7
            minor == 18 -> 8
            minor == 19 -> when {
                patch <= 2 -> 9
                patch == 3 -> 12
                else -> 13
            }
            minor == 20 -> when {
                patch <= 1 -> 15
                patch == 2 -> 18
                patch <= 4 -> 22
                else -> 32
            }
            minor == 21 -> when {
                patch <= 1 -> 34
                patch <= 3 -> 42
                patch == 4 -> 46
                patch == 5 -> 55
                patch == 6 -> 63
                patch <= 8 -> 64
                patch <= 10 -> 69
                else -> 75
            }
            else -> 75
        }
    }

    fun updateOptionsResourcePack(
        optionsPath: File,
        minecraftVersion: String?,
        enabled: Boolean
    ) {
        val lines = if (optionsPath.isFile) {
            optionsPath.readLines().toMutableList()
        } else {
            mutableListOf()
        }
        val lineIndex = lines.indexOfFirst { it.startsWith("resourcePacks:", ignoreCase = true) }
        if (!enabled && (lineIndex < 0 || !lines[lineIndex].contains(PACK_FILE_NAME, ignoreCase = true))) {
            return
        }
        val packs = mutableListOf<String>()
        if (lineIndex >= 0) {
            val raw = lines[lineIndex].substringAfter(':').trim()
            runCatching {
                val arr = JSONArray(if (raw.isBlank()) "[]" else raw)
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { packs.add(it) }
                }
            }
        }
        packs.removeAll {
            it.equals(MODERN_PACK_ID, ignoreCase = true) ||
                it.equals(LEGACY_PACK_ID, ignoreCase = true)
        }
        if (enabled) {
            val (minor, _) = versionParts(minecraftVersion)
            packs.add(if (minor >= 13 || minor < 0) MODERN_PACK_ID else LEGACY_PACK_ID)
        }
        val newLine = "resourcePacks:" + JSONArray(packs).toString()
        if (lineIndex >= 0) {
            lines[lineIndex] = newLine
        } else {
            lines.add(newLine)
        }
        optionsPath.parentFile?.mkdirs()
        optionsPath.writeText(lines.joinToString("\n") + "\n")
    }

    private fun buildSkinResourcePack(
        packPath: File,
        skinFile: File,
        minecraftVersion: String?,
        packFormat: Int,
        slim: Boolean,
        applyToBothModernModels: Boolean
    ) {
        var skinBytes = prepareSkinBytesForVersion(skinFile.readBytes(), minecraftVersion)
        val validation = validateSkinBytes(skinBytes)
        require(validation.isValid) { validation.message }
        skinBytes = normalizeToPng(skinBytes)

        val tmp = File(packPath.parentFile, "${packPath.name}.${System.nanoTime()}.tmp")
        try {
            ZipOutputStream(FileOutputStream(tmp)).use { zip ->
                val packMeta = JSONObject()
                val pack = JSONObject()
                    .put("pack_format", packFormat)
                    .put("description", "Booxin 自定义离线皮肤资源包")
                if (usesModernPackMetadata(minecraftVersion, packFormat)) {
                    pack.put("min_format", packFormat)
                    pack.put("max_format", packFormat)
                }
                packMeta.put("pack", pack)
                writeZip(zip, "pack.mcmeta", packMeta.toString(2).toByteArray(Charsets.UTF_8))

                val (minor, patch) = versionParts(minecraftVersion)
                val modern = minor > 19 || (minor == 19 && patch >= 3) || minor < 0
                if (modern) {
                    val models = if (applyToBothModernModels) {
                        listOf("slim", "wide")
                    } else {
                        listOf(if (slim) "slim" else "wide")
                    }
                    for (modelDir in models) {
                        for (skinName in DEFAULT_SKIN_NAMES) {
                            writeZip(
                                zip,
                                "assets/minecraft/textures/entity/player/$modelDir/$skinName.png",
                                skinBytes
                            )
                        }
                    }
                } else {
                    val name = if (slim) "alex" else "steve"
                    writeZip(zip, "assets/minecraft/textures/entity/$name.png", skinBytes)
                }
            }
            if (packPath.exists()) packPath.delete()
            if (!tmp.renameTo(packPath)) {
                tmp.copyTo(packPath, overwrite = true)
                tmp.delete()
            }
        } finally {
            tmp.takeIf { it.exists() }?.delete()
        }
    }

    private fun prepareSkinBytesForVersion(source: ByteArray, minecraftVersion: String?): ByteArray {
        val (minor, _) = versionParts(minecraftVersion)
        if (minor !in 6..7) return source
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val bmp = BitmapFactory.decodeByteArray(source, 0, source.size, opts) ?: return source
        return try {
            if (bmp.height * 2 == bmp.width) {
                source
            } else {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height / 2)
                try {
                    val out = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                } finally {
                    if (!cropped.isRecycled) cropped.recycle()
                }
            }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun writeZip(zip: ZipOutputStream, path: String, content: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content)
        zip.closeEntry()
    }

    private fun usesModernPackMetadata(minecraftVersion: String?, packFormat: Int): Boolean =
        packFormat > 64 && yearBased(minecraftVersion) != null

    private fun versionParts(minecraftVersion: String?): Pair<Int, Int> {
        val parsed = minecraftVersion?.let { MinecraftJavaRequirement.parseVersion(it) }
        if (parsed != null && parsed.first == 1) {
            return parsed.second to parsed.third
        }
        // Fallback: find 1.x in string without mistaking year versions.
        val m = Regex("""(?:^|[^0-9.])1\.(\d+)(?:\.(\d+))?""")
            .find(minecraftVersion.orEmpty())
            ?: return -1 to 0
        val minor = m.groupValues[1].toIntOrNull() ?: return -1 to 0
        val patch = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        return minor to patch
    }

    private fun yearBased(minecraftVersion: String?): Triple<Int, Int, String>? {
        val m = Regex("""(\d+)\.(\d+)(.*)""")
            .find(minecraftVersion.orEmpty())
            ?: return null
        val year = m.groupValues[1].toIntOrNull() ?: return null
        val minor = m.groupValues[2].toIntOrNull() ?: return null
        if (year < 26) return null
        return Triple(year, minor, m.groupValues[3])
    }

    private fun isUuid(uuid: String?): Boolean {
        val n = normalizeUuid(uuid)
        return n.length == 32 && n.all { it.isDigit() || it in 'a'..'f' }
    }

    private fun normalizeUuid(uuid: String?): String =
        (uuid ?: "").replace("-", "").trim().lowercase(Locale.US)

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }

    private fun computeJavaUuidHashCode(normalizedUuid: String): Int {
        val most = normalizedUuid.substring(0, 16).toULong(16)
        val least = normalizedUuid.substring(16).toULong(16)
        val hash = ((most shr 32).toUInt()) xor
            (most.toUInt()) xor
            ((least shr 32).toUInt()) xor
            (least.toUInt())
        return hash.toInt()
    }

    /** Resolve vanilla-ish MC version id from an installed version folder name / json. */
    fun resolveMinecraftVersion(versionId: String): String {
        val root = File(com.booxin.launcher.core.LauncherPaths.versionsDir, versionId)
        val json = File(root, "$versionId.json")
        if (json.isFile) {
            runCatching {
                val obj = JSONObject(json.readText())
                val inherits = obj.optString("inheritsFrom").ifBlank { null }
                if (inherits != null) return inherits
                val id = obj.optString("id").ifBlank { null }
                if (id != null && MinecraftJavaRequirement.parseVersion(id) != null) return id
            }
        }
        return MinecraftJavaRequirement.parseVersion(versionId)?.let { (a, b, c) ->
            if (c == 0) "$a.$b" else "$a.$b.$c"
        } ?: versionId
    }
}
