package com.booxin.launcher.core.java

import com.booxin.launcher.core.net.FileDownloader
import okhttp3.OkHttpClient
import java.io.File

/**
 * Backward-compatible wrapper around [FileDownloader] for Java archives.
 */
class JavaDownloader(
    client: OkHttpClient = com.booxin.launcher.core.net.HttpClients.shared
) {
    private val delegate = FileDownloader(client)

    suspend fun download(
        url: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Result<File> = delegate.download(url, destination, onProgress)
}
