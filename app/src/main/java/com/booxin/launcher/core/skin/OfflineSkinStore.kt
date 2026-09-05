package com.booxin.launcher.core.skin

import android.content.Context
import android.net.Uri
import com.booxin.launcher.core.LauncherPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Offline account skin storage.
 *
 * Vanilla path: [OfflineSkinLaunch] + authlib-injector (JVM agent, not a mod).
 * Optional: also copies into CustomSkinLoader LocalSkin for users who install CSL.
 */
object OfflineSkinStore {

    private val localSkinEntry: JSONObject
        get() = JSONObject()
            .put("name", "LocalSkin")
            .put("type", "Legacy")
            .put("checkPNG", false)
            .put("skin", "LocalSkin/skins/{USERNAME}.png")
            .put("model", "auto")
            .put("cape", "LocalSkin/capes/{USERNAME}.png")
            .put("elytra", "LocalSkin/elytras/{USERNAME}.png")

    fun skinsDir(): File = File(LauncherPaths.rootDir, "skins").also { it.mkdirs() }

    fun skinFile(accountId: String): File = File(skinsDir(), "$accountId.png")

    fun hasSkin(accountId: String): Boolean = skinFile(accountId).isFile

    fun importFromUri(context: Context, accountId: String, uri: Uri): Result<File> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取图片")
        require(bytes.isNotEmpty()) { "图片为空" }
        val validation = OfflineSkinService.validateSkinBytes(bytes)
        require(validation.isValid) { validation.message }
        val png = OfflineSkinService.normalizeToPng(bytes)
        val out = skinFile(accountId)
        out.parentFile?.mkdirs()
        out.writeBytes(png)
        out
    }

    fun saveBytes(accountId: String, bytes: ByteArray): File {
        val validation = OfflineSkinService.validateSkinBytes(bytes)
        require(validation.isValid) { validation.message }
        val png = OfflineSkinService.normalizeToPng(bytes)
        val out = skinFile(accountId)
        out.parentFile?.mkdirs()
        out.writeBytes(png)
        return out
    }

    fun clear(accountId: String, username: String? = null) {
        skinFile(accountId).takeIf { it.isFile }?.delete()
        val name = username?.trim()?.takeIf { it.isNotEmpty() } ?: return
        File(skinsDir(), "by-name/$name.png").takeIf { it.isFile }?.delete()
        // Best-effort: remove previously installed CSL copies under root game folder.
        File(LauncherPaths.rootDir, "CustomSkinLoader/LocalSkin/skins/$name.png")
            .takeIf { it.isFile }?.delete()
        // Legacy mistaken path from older builds.
        File(LauncherPaths.rootDir, "CustomSkinLoader/LocalSkin/$name.png")
            .takeIf { it.isFile }?.delete()
    }

    /**
     * Install skin where CustomSkinLoader looks for offline local skins, and
     * also under the version game dir for instance-scoped loaders.
     * Also keeps a by-name copy so the :game process can resolve without accounts prefs.
     */
    fun installForLaunch(accountId: String, username: String, versionGameDir: File) {
        val src = skinFile(accountId)
        if (!src.isFile) return
        val safeName = username.trim().ifBlank { "Player" }
        val byName = File(skinsDir(), "by-name/$safeName.png")
        byName.parentFile?.mkdirs()
        src.copyTo(byName, overwrite = true)
        val bases = listOf(LauncherPaths.rootDir, versionGameDir).distinctBy { it.absolutePath }
        bases.forEach { base ->
            val dest = File(base, "CustomSkinLoader/LocalSkin/skins/$safeName.png")
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            // Remove mistaken flat path from older launcher builds.
            File(base, "CustomSkinLoader/LocalSkin/$safeName.png").takeIf { it.isFile }?.delete()
            ensureLocalSkinFirst(File(base, "CustomSkinLoader/CustomSkinLoader.json"))
        }
    }

    /**
     * Ensure CustomSkinLoader.json exists and LocalSkin is first in loadlist,
     * so offline local skins win over Mojang / remote APIs.
     */
    private fun ensureLocalSkinFirst(configFile: File) {
        runCatching {
            configFile.parentFile?.mkdirs()
            val root = if (configFile.isFile) {
                JSONObject(configFile.readText())
            } else {
                JSONObject().put("version", "14.15")
            }
            val existing = root.optJSONArray("loadlist") ?: JSONArray()
            val rebuilt = JSONArray()
            rebuilt.put(localSkinEntry)
            for (i in 0 until existing.length()) {
                val entry = existing.optJSONObject(i) ?: continue
                if (entry.optString("name").equals("LocalSkin", ignoreCase = true)) continue
                rebuilt.put(entry)
            }
            // Keep a sensible fallback if the file was empty / brand new.
            if (rebuilt.length() == 1) {
                rebuilt.put(
                    JSONObject()
                        .put("name", "Mojang")
                        .put("type", "MojangAPI")
                )
            }
            root.put("loadlist", rebuilt)
            // Enable cache so skins still resolve when remote APIs are unreachable.
            if (!root.has("enableLocalProfileCache")) {
                root.put("enableLocalProfileCache", true)
            }
            configFile.writeText(root.toString(2))
        }
    }
}
