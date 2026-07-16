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
 * Extracts JRE archives used by Android OpenJDK packages (.tar.xz / .zip),
 * including FCL/Pojav split packages (`universal` + `bin-{abi}`).
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

    /**
     * Install FCL-style split JRE zip into [destinationDir].
     * Expected zip entries: version, universal.tar.xz, bin-{abi}.tar.xz
     */
    suspend fun installPojavSplit(
        zipArchive: File,
        destinationDir: File,
        abi: JavaAbi
    ) = withContext(Dispatchers.IO) {
        if (!zipArchive.exists()) throw IOException("压缩包不存在: ${zipArchive.absolutePath}")
        val workDir = File(zipArchive.parentFile, "${zipArchive.nameWithoutExtension}-unpack")
        if (workDir.exists()) workDir.deleteRecursively()
        workDir.mkdirs()
        try {
            extractZip(zipArchive, workDir)
            val universal = File(workDir, "universal.tar.xz")
            val archFile = File(workDir, "bin-${abi.packageToken}.tar.xz")
            if (!universal.exists()) throw IOException("分包缺少 universal.tar.xz")
            if (!archFile.exists()) {
                throw IOException("当前 ABI(${abi.packageToken}) 缺少 ${archFile.name}")
            }
            if (destinationDir.exists()) destinationDir.deleteRecursively()
            destinationDir.mkdirs()
            extractTarXz(universal, destinationDir, wipeDestination = false)
            extractTarXz(archFile, destinationDir, wipeDestination = false)
            val versionFile = File(workDir, "version")
            if (versionFile.exists()) {
                versionFile.copyTo(File(destinationDir, "version"), overwrite = true)
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun extractTarXz(
        archive: File,
        destinationDir: File,
        wipeDestination: Boolean = true
    ) {
        if (wipeDestination) {
            if (destinationDir.exists()) destinationDir.deleteRecursively()
            destinationDir.mkdirs()
        }
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
