package com.booxin.launcher.core.java

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Extracts JRE archives used by Android OpenJDK packages (.tar.xz / .zip).
 */
object ArchiveExtractor {

    suspend fun extract(archive: File, destinationDir: File) = withContext(Dispatchers.IO) {
        if (!archive.exists()) throw IOException("压缩包不存在: ${archive.absolutePath}")
        if (destinationDir.exists()) {
            destinationDir.deleteRecursively()
        }
        destinationDir.mkdirs()

        val name = archive.name.lowercase()
        when {
            name.endsWith(".tar.xz") || name.endsWith(".txz") -> extractTarXz(archive, destinationDir)
            name.endsWith(".zip") -> extractZip(archive, destinationDir)
            else -> throw IOException("不支持的压缩格式: ${archive.name}")
        }

        // Some packages nest a single root folder (e.g. jre-17.0.x). Flatten if useful.
        flattenSingleRootIfNeeded(destinationDir)
    }

    private suspend fun extractTarXz(archive: File, destinationDir: File) {
        FileInputStream(archive).use { fis ->
            BufferedInputStream(fis).use { bis ->
                XZCompressorInputStream(bis).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        var entry: TarArchiveEntry? = tar.nextEntry
                        while (entry != null) {
                            coroutineContext.ensureActive()
                            writeTarEntry(tar, entry, destinationDir)
                            entry = tar.nextEntry
                        }
                    }
                }
            }
        }
    }

    private fun writeTarEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        destinationDir: File
    ) {
        val outFile = resolveSafe(destinationDir, entry.name)
        if (entry.isDirectory) {
            outFile.mkdirs()
            return
        }
        if (entry.isSymbolicLink) {
            // Skip symlinks on Android storage; binaries we need are real files.
            return
        }
        outFile.parentFile?.mkdirs()
        outFile.outputStream().use { output ->
            tar.copyTo(output)
        }
        if (entry.mode and 0b001_001_001 != 0) {
            outFile.setExecutable(true, false)
        }
    }

    private suspend fun extractZip(archive: File, destinationDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                coroutineContext.ensureActive()
                val outFile = resolveSafe(destinationDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun resolveSafe(base: File, entryName: String): File {
        val target = File(base, entryName).canonicalFile
        val basePath = base.canonicalFile
        if (!target.path.startsWith(basePath.path + File.separator) && target != basePath) {
            throw IOException("非法压缩路径: $entryName")
        }
        return target
    }

    private fun flattenSingleRootIfNeeded(destinationDir: File) {
        val children = destinationDir.listFiles()?.filter { it.name != "." && it.name != ".." } ?: return
        if (children.size != 1 || !children[0].isDirectory) return
        val root = children[0]
        // Prefer flatten only when it looks like a JRE root.
        val looksLikeJre = File(root, "bin").exists() || File(root, "lib").exists()
        if (!looksLikeJre) return
        root.listFiles()?.forEach { child ->
            val target = File(destinationDir, child.name)
            if (target.exists()) target.deleteRecursively()
            child.renameTo(target)
        }
        root.deleteRecursively()
    }
}
