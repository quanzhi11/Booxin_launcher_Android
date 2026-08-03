package com.booxin.launcher.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Streams a remote file to disk with progress callbacks.
 * Shared by Java runtime and vanilla game download pipelines.
 */
class FileDownloader(
    private val client: OkHttpClient = HttpClients.shared
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
                .header("User-Agent", HttpClients.USER_AGENT)
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

    suspend fun downloadText(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("请求失败 HTTP ${response.code}: $url")
                }
                response.body?.string() ?: throw IOException("空响应体: $url")
            }
        }
    }

    companion object {
        private const val PROGRESS_STEP = 256 * 1024L
    }
}
