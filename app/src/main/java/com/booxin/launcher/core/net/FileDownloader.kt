package com.booxin.launcher.core.net

import com.booxin.launcher.core.download.DownloadProviders
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
    ): Result<File> = download(listOf(url), destination, onProgress)

    /**
     * Try [urls] in order. Non-final candidates use a short timeout so a slow
     * primary does not block fallback mirrors for minutes.
     */
    suspend fun download(
        urls: List<String>,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
            return@withContext Result.failure(IOException("无下载地址"))
        }
        var lastError: Throwable? = null
        for ((index, url) in urls.withIndex()) {
            coroutineContext.ensureActive()
            val isLast = index == urls.lastIndex
            val attemptClient = if (isLast || urls.size == 1) client else HttpClients.cascadeAttempt
            val result = downloadOnce(attemptClient, url, destination, onProgress)
            if (result.isSuccess) {
                DownloadProviders.noteDownloadOutcome(url, success = true)
                return@withContext result
            }
            lastError = result.exceptionOrNull()
            DownloadProviders.noteDownloadOutcome(url, success = false)
            // Drop partial so the next candidate starts clean.
            File(destination.parentFile, destination.name + ".part").delete()
        }
        Result.failure(lastError ?: IOException("下载失败"))
    }

    suspend fun downloadText(url: String): Result<String> = downloadText(listOf(url))

    suspend fun downloadText(urls: List<String>): Result<String> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
            return@withContext Result.failure(IOException("无请求地址"))
        }
        var lastError: Throwable? = null
        for ((index, url) in urls.withIndex()) {
            coroutineContext.ensureActive()
            val isLast = index == urls.lastIndex
            val attemptClient = if (isLast || urls.size == 1) client else HttpClients.cascadeAttempt
            val result = try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", HttpClients.USER_AGENT)
                    .get()
                    .build()
                attemptClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("请求失败 HTTP ${response.code}: $url")
                    }
                    val body = response.body?.string() ?: throw IOException("空响应体: $url")
                    Result.success(body)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (result.isSuccess) {
                DownloadProviders.noteDownloadOutcome(url, success = true)
                return@withContext result
            }
            lastError = result.exceptionOrNull()
            DownloadProviders.noteDownloadOutcome(url, success = false)
        }
        Result.failure(lastError ?: IOException("请求失败"))
    }

    private suspend fun downloadOnce(
        attemptClient: OkHttpClient,
        url: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> {
        return try {
            destination.parentFile?.mkdirs()
            val partial = File(destination.parentFile, destination.name + ".part")
            if (partial.exists()) partial.delete()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.USER_AGENT)
                .get()
                .build()

            attemptClient.newCall(request).execute().use { response ->
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
            Result.success(destination)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    companion object {
        private const val PROGRESS_STEP = 256 * 1024L
    }
}
