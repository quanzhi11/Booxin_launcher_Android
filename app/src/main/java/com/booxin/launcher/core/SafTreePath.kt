package com.booxin.launcher.core

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * Resolve SAF tree/document URIs to real filesystem paths when possible.
 * Minecraft / JVM need absolute paths, not content:// URIs.
 */
object SafTreePath {

    fun toAbsolutePath(uri: Uri): String? {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path?.takeIf { it.isNotBlank() }
        }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: return null
        return documentIdToPath(docId)
    }

    private fun documentIdToPath(docId: String): String? {
        val id = docId.trim()
        when {
            id.startsWith("raw:", ignoreCase = true) ->
                return id.substringAfter(':').takeIf { it.startsWith('/') }
            id.startsWith('/') ->
                return id
        }
        val sep = id.indexOf(':')
        if (sep <= 0) return null
        val volume = id.substring(0, sep)
        val relative = id.substring(sep + 1).trimStart('/')
        val base = when {
            volume.equals("primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory().absolutePath
            // Common secondary volume id: XXXX-XXXX
            else -> "/storage/$volume"
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }
}
