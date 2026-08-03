package com.booxin.launcher.core.skin

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.booxin.launcher.core.LauncherPaths
import java.io.File
import java.io.FileOutputStream

/**
 * Offline account skin storage + launch-time install into CustomSkinLoader paths.
 */
object OfflineSkinStore {

    fun skinsDir(): File = File(LauncherPaths.rootDir, "skins").also { it.mkdirs() }

    fun skinFile(accountId: String): File = File(skinsDir(), "$accountId.png")

    fun hasSkin(accountId: String): Boolean = skinFile(accountId).isFile

    fun importFromUri(context: Context, accountId: String, uri: Uri): Result<File> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取图片")
        require(bytes.isNotEmpty()) { "图片为空" }
        // Validate PNG (or any decodeable bitmap); re-encode as PNG for consistency.
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("无法解析图片，请选择 PNG/JPG 皮肤")
        val out = skinFile(accountId)
        FileOutputStream(out).use { fos ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
        }
        if (!bitmap.isRecycled) bitmap.recycle()
        out
    }

    fun clear(accountId: String) {
        skinFile(accountId).takeIf { it.isFile }?.delete()
    }

    /**
     * Install skin where CustomSkinLoader looks for offline local skins, and
     * also under the version game dir for instance-scoped loaders.
     */
    fun installForLaunch(accountId: String, username: String, versionGameDir: File) {
        val src = skinFile(accountId)
        if (!src.isFile) return
        val safeName = username.trim().ifBlank { "Player" }
        val targets = listOf(
            File(LauncherPaths.rootDir, "CustomSkinLoader/LocalSkin/$safeName.png"),
            File(versionGameDir, "CustomSkinLoader/LocalSkin/$safeName.png"),
            File(LauncherPaths.rootDir, "CustomSkinLoader/LocalSkin/${safeName}.png"),
        )
        targets.forEach { dest ->
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
    }
}
