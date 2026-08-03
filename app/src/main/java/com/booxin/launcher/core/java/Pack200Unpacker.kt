package com.booxin.launcher.core.java

import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarOutputStream
import java.util.jar.Pack200

/**
 * Unpacks Java 8 pack200 `.jar.pack` files.
 *
 * Prefer in-process [Pack200.Unpacker] (no native binary in the APK).
 * Fall back to JRE's `bin/unpack200` via `/system/bin/linker64` because
 * Android 10+ SELinux blocks direct exec from app data.
 */
object Pack200Unpacker {
    private const val TAG = "Pack200Unpacker"

    fun needsUnpack(home: File): Boolean {
        return findPackFiles(home).isNotEmpty()
    }

    fun unpackAll(home: File) {
        val packs = findPackFiles(home)
        if (packs.isEmpty()) return

        Log.i(TAG, "Unpacking ${packs.size} pack file(s) under ${home.absolutePath}")

        val apiError = runCatching {
            unpackWithApi(packs)
        }.exceptionOrNull()
        if (apiError == null && findPackFiles(home).isEmpty()) {
            return
        }

        val remaining = findPackFiles(home)
        if (remaining.isEmpty()) return

        Log.w(
            TAG,
            "Pack200 API incomplete (${apiError?.message ?: "unknown"}); trying linker+unpack200 for ${remaining.size} file(s)"
        )
        unpackWithNative(home, remaining)
    }

    @Suppress("DEPRECATION")
    private fun unpackWithApi(packs: List<File>) {
        val unpacker = Pack200.newUnpacker()
        for (pack in packs) {
            val out = outputJarFor(pack)
            if (out.exists()) out.delete()
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { fos ->
                JarOutputStream(fos).use { jos ->
                    unpacker.unpack(pack, jos)
                }
            }
            if (!out.exists() || out.length() == 0L) {
                error("Pack200 API 输出为空: ${pack.name}")
            }
            if (!pack.delete()) {
                Log.w(TAG, "Unpacked ok but failed to delete ${pack.absolutePath}")
            }
            Log.i(TAG, "API unpacked ${pack.name} -> ${out.name} (${out.length()} bytes)")
        }
    }

    private fun unpackWithNative(home: File, packs: List<File>) {
        val unpack200 = listOf(
            File(home, "bin/unpack200"),
            File(home, "jre/bin/unpack200")
        ).firstOrNull { it.isFile }
            ?: throw IllegalStateException("Java 运行时缺少 bin/unpack200，无法解压 .pack")

        runCatching { Os.chmod(unpack200.absolutePath, 493) }

        val libDirs = listOf(
            File(home, "lib/aarch64"),
            File(home, "lib/arm"),
            File(home, "lib/i386"),
            File(home, "lib/amd64"),
            File(home, "jre/lib/aarch64"),
            File(home, "jre/lib/arm"),
            File(home, "lib")
        ).filter { it.isDirectory }
        val ldPath = libDirs.joinToString(File.pathSeparator) { it.absolutePath }
        val linker = preferLinker()
            ?: throw IllegalStateException("系统缺少 linker，无法执行 unpack200")

        Log.i(TAG, "Native unpack via $linker + ${unpack200.absolutePath}")

        for (pack in packs) {
            val out = outputJarFor(pack)
            if (out.exists()) out.delete()
            val pb = ProcessBuilder(
                linker,
                unpack200.absolutePath,
                "-r",
                pack.absolutePath,
                out.absolutePath
            ).directory(home).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = listOfNotNull(
                ldPath,
                pb.environment()["LD_LIBRARY_PATH"]
            ).filter { it.isNotBlank() }.joinToString(File.pathSeparator)

            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            if (code != 0 || !out.exists() || out.length() == 0L) {
                throw IllegalStateException(
                    "unpack200 失败 ($code): ${pack.name}" +
                        if (output.isNotEmpty()) " — $output" else ""
                )
            }
            Log.i(TAG, "Native unpacked ${pack.name} -> ${out.name} (${out.length()} bytes)")
        }
    }

    private fun preferLinker(): String? {
        val candidates = if (Build.SUPPORTED_ABIS.firstOrNull()?.contains("64") == true) {
            listOf("/system/bin/linker64", "/system/bin/linker")
        } else {
            listOf("/system/bin/linker", "/system/bin/linker64")
        }
        return candidates.firstOrNull { File(it).canExecute() }
    }

    private fun outputJarFor(pack: File): File {
        val path = pack.path
        val base = when {
            path.endsWith(".pack.gz", ignoreCase = true) -> path.dropLast(".pack.gz".length)
            path.endsWith(".pack", ignoreCase = true) -> path.dropLast(".pack".length)
            else -> "$path.out"
        }
        return File(base)
    }

    private fun findPackFiles(home: File): List<File> {
        if (!home.isDirectory) return emptyList()
        return home.walkTopDown()
            .maxDepth(8)
            .filter { it.isFile && (it.name.endsWith(".pack") || it.name.endsWith(".pack.gz")) }
            .toList()
    }
}
