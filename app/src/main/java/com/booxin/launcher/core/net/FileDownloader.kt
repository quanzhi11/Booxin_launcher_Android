package com.booxin.launcher.core.net

import android.util.Log
import com.booxin.launcher.core.download.DownloadProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * 下载文件到本地，带进度回调。
 * 大文件在支持 Range 时多分片；小文件或非 Range 主机走单连接。
 */
class FileDownloader(
    private val client: OkHttpClient = HttpClients.shared
) {

    suspend fun download(
        url: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        accelerate: Boolean = true
    ): Result<File> = download(listOf(url), destination, onProgress, accelerate)

    /** 按顺序尝试 [urls]；非最后一个源用短超时，避免卡住备用镜像。 */
    suspend fun download(
        urls: List<String>,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        accelerate: Boolean = true
    ): Result<File> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
            return@withContext Result.failure(IOException("无下载地址"))
        }
        var lastError: Throwable? = null
        for ((index, url) in urls.withIndex()) {
            coroutineContext.ensureActive()
            val isLast = index == urls.lastIndex
            val attemptClient = if (isLast || urls.size == 1) client else HttpClients.cascadeAttempt
            val result = downloadOnce(attemptClient, url, destination, onProgress, accelerate)
            if (result.isSuccess) {
                DownloadProviders.noteDownloadOutcome(url, success = true)
                return@withContext result
            }
            lastError = result.exceptionOrNull()
            DownloadProviders.noteDownloadOutcome(url, success = false)
            // Drop partial so the next candidate starts clean.
            cleanupPartials(destination)
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
        onProgress: (downloaded: Long, total: Long) -> Unit,
        accelerate: Boolean
    ): Result<File> {
        cleanupPartials(destination)
        destination.parentFile?.mkdirs()

        if (accelerate) {
            val probe = probeForMultipart(attemptClient, url)
            if (probe != null && probe.totalBytes >= MULTIPART_MIN_SIZE) {
                val connections = connectionCount(probe.totalBytes)
                if (connections > 1) {
                    Log.i(
                        TAG,
                        "multipart download: $connections parts, ${probe.totalBytes} bytes ← $url"
                    )
                    val multi = downloadMultipart(
                        attemptClient,
                        url,
                        destination,
                        probe.totalBytes,
                        connections,
                        onProgress
                    )
                    if (multi.isSuccess) return multi
                    Log.w(TAG, "multipart failed, fallback single: ${multi.exceptionOrNull()?.message}")
                    cleanupPartials(destination)
                }
            }
        }
        return downloadSingle(attemptClient, url, destination, onProgress)
    }

    /** Range 探测：206 则返回总大小，否则单连接。 */
    private fun probeForMultipart(attemptClient: OkHttpClient, url: String): RangeProbe? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.USER_AGENT)
                .header("Range", "bytes=0-0")
                .get()
                .build()
            attemptClient.newCall(request).execute().use { response ->
                when (response.code) {
                    206 -> {
                        // Content-Range: bytes 0-0/TOTAL is authoritative.
                        val fromRange = parseContentRangeTotal(response.header("Content-Range"))
                        if (fromRange != null && fromRange > 1L) {
                            RangeProbe(fromRange)
                        } else {
                            null
                        }
                    }
                    // 200 表示不支持 Range，改单连接。
                    else -> null
                }
            }
        } catch (error: Exception) {
            Log.d(TAG, "range probe failed: ${error.message}")
            null
        }
    }

    private suspend fun downloadMultipart(
        attemptClient: OkHttpClient,
        url: String,
        destination: File,
        totalBytes: Long,
        connections: Int,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> = try {
        val partial = File(destination.parentFile, destination.name + ".part")
        if (partial.exists()) partial.delete()

        RandomAccessFile(partial, "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        val ranges = splitRanges(totalBytes, connections)
        val downloaded = AtomicLong(0L)
        val lastEmit = AtomicLong(0L)

        coroutineScope {
            ranges.map { range ->
                async(Dispatchers.IO) {
                    downloadSegment(
                        attemptClient = attemptClient,
                        url = url,
                        partial = partial,
                        start = range.first,
                        endInclusive = range.second,
                        totalBytes = totalBytes,
                        downloaded = downloaded,
                        lastEmit = lastEmit,
                        onProgress = onProgress
                    )
                }
            }.awaitAll()
        }

        onProgress(totalBytes, totalBytes)
        finalizePartial(partial, destination)
        Result.success(destination)
    } catch (error: kotlinx.coroutines.CancellationException) {
        cleanupPartials(destination)
        throw error
    } catch (error: Exception) {
        cleanupPartials(destination)
        Result.failure(error)
    }

    private suspend fun downloadSegment(
        attemptClient: OkHttpClient,
        url: String,
        partial: File,
        start: Long,
        endInclusive: Long,
        totalBytes: Long,
        downloaded: AtomicLong,
        lastEmit: AtomicLong,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.USER_AGENT)
            .header("Range", "bytes=$start-$endInclusive")
            .get()
            .build()

        attemptClient.newCall(request).execute().use { response ->
            // Require 206 Partial Content for every segment.
            if (response.code != 206) {
                throw IOException("服务器未按 Range 响应 HTTP ${response.code}，放弃多分片")
            }
            val body = response.body ?: throw IOException("空响应体: $url")
            val expected = endInclusive - start + 1
            var written = 0L
            body.byteStream().use { input ->
                RandomAccessFile(partial, "rw").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (written < expected) {
                        coroutineContext.ensureActive()
                        val toRead = min(buffer.size.toLong(), expected - written).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        written += read
                        val done = downloaded.addAndGet(read.toLong())
                        val prev = lastEmit.get()
                        if (done - prev >= PROGRESS_STEP || done >= totalBytes) {
                            if (lastEmit.compareAndSet(prev, done)) {
                                onProgress(done, totalBytes)
                            }
                        }
                    }
                }
            }
            if (written != expected) {
                throw IOException("分段不完整: got $written expected $expected [$start-$endInclusive]")
            }
        }
    }

    private suspend fun downloadSingle(
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

            finalizePartial(partial, destination)
            Result.success(destination)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun finalizePartial(partial: File, destination: File) {
        if (destination.exists()) destination.delete()
        if (!partial.renameTo(destination)) {
            partial.copyTo(destination, overwrite = true)
            partial.delete()
        }
    }

    private fun cleanupPartials(destination: File) {
        File(destination.parentFile, destination.name + ".part").delete()
    }

    companion object {
        private const val TAG = "FileDownloader"
        private const val PROGRESS_STEP = 256 * 1024L
        private const val MULTIPART_MIN_SIZE = 2L * 1024 * 1024
        private const val MULTIPART_MAX_CONNECTIONS = 8
        private const val MULTIPART_MIN_PART = 512L * 1024

        private fun connectionCount(totalBytes: Long): Int {
            val bySize = (totalBytes / MULTIPART_MIN_PART).toInt().coerceAtLeast(1)
            return min(MULTIPART_MAX_CONNECTIONS, bySize)
        }

        private fun splitRanges(totalBytes: Long, connections: Int): List<Pair<Long, Long>> {
            val part = totalBytes / connections
            return (0 until connections).map { i ->
                val start = i * part
                val end = if (i == connections - 1) totalBytes - 1 else (start + part - 1)
                start to end
            }
        }

        private fun parseContentRangeTotal(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            // bytes 0-0/12345  or  bytes */12345
            val slash = header.lastIndexOf('/')
            if (slash < 0 || slash == header.lastIndex) return null
            return header.substring(slash + 1).trim().toLongOrNull()?.takeIf { it > 0L }
        }
    }

    private data class RangeProbe(val totalBytes: Long)
}
