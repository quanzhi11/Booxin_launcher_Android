package com.booxin.launcher.core.java

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Streams a remote Java runtime archive to disk with progress callbacks.
 */
class JavaDownloader(
    private val client: OkHttpClient = defaultClient()
) {

    suspend fun download(
        url: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destination.parentFile?.mkdirs()
            val partial = File(destination.parentFile, destination.name + ".part")
            if (partial.exists()) partial.delete()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BooxinLauncher/0.1 (Android)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("下载失败 HTTP ${response.code}: $url")
                }
                val body = response.body ?: throw IOException("空响应体: $url")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastEmit = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastEmit >= PROGRESS_STEP || downloaded == total) {
                                onProgress(downloaded, total)
                                lastEmit = downloaded
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            destination
        }
    }

    companion object {
        private const val PROGRESS_STEP = 256 * 1024L

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
