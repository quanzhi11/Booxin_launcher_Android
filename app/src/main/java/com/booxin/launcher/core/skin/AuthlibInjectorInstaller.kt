package com.booxin.launcher.core.skin

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.net.FileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Ensures authlib-injector.jar is available for vanilla offline skins.
 * This is a JVM javaagent (not a Minecraft mod).
 */
object AuthlibInjectorInstaller {

    private const val VERSION = "1.2.8"
    private const val BUILD = 56
    private const val SHA256 =
        "9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10"

    private val downloader = FileDownloader()

    fun jarFile(): File =
        File(LauncherPaths.runtimeDir, "authlib-injector/authlib-injector-$VERSION.jar")

    fun isReady(): Boolean {
        val jar = jarFile()
        return jar.isFile && jar.length() > 0L && sha256(jar).equals(SHA256, ignoreCase = true)
    }

    suspend fun ensure(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val jar = jarFile()
            if (isReady()) return@runCatching jar
            jar.parentFile?.mkdirs()
            val tmp = File(jar.parentFile, "${jar.name}.part")
            tmp.delete()
            val urls = listOf(
                "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/$BUILD/authlib-injector-$VERSION.jar",
                "https://authlib-injector.yushi.moe/artifact/$BUILD/authlib-injector-$VERSION.jar"
            )
            downloader.download(urls, tmp).getOrThrow()
            val hash = sha256(tmp)
            require(hash.equals(SHA256, ignoreCase = true)) {
                "authlib-injector 校验失败: $hash"
            }
            if (jar.exists()) jar.delete()
            require(tmp.renameTo(jar) || (tmp.copyTo(jar, overwrite = true).also { tmp.delete() }).isFile) {
                "无法写入 ${jar.absolutePath}"
            }
            jar
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
