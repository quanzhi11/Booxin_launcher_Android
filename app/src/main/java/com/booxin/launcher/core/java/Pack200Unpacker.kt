package com.booxin.launcher.core.java

import android.util.Log
import java.io.File

/**
 * Unpacks Java 8 pack200 `.jar.pack` files using the runtime's `bin/unpack200`.
 *
 * Pojav/FCL JRE8 packages ship jars as pack200 to shrink download size;
 * without this step HotSpot fails with `NoClassDefFoundError: java/lang/Object`.
 */
object Pack200Unpacker {
    private const val TAG = "Pack200Unpacker"

    fun needsUnpack(home: File): Boolean {
        return findPackFiles(home).isNotEmpty()
    }

    /**
     * Unpacks every `*.pack` under [home] in-place (`foo.jar.pack` → `foo.jar`)
     * and deletes the `.pack` source when unpack succeeds (unpack200 `-r`).
     */
    fun unpackAll(home: File) {
        val packs = findPackFiles(home)
        if (packs.isEmpty()) return

        val unpack200 = findUnpack200(home)
            ?: throw IllegalStateException("Java 运行时缺少 unpack200，无法解压 .pack: ${home.absolutePath}")

        val libDirs = listOf(
            File(home, "lib/aarch64"),
            File(home, "lib/arm"),
            File(home, "lib/i386"),
            File(home, "lib/amd64"),
            File(home, "lib")
        ).filter { it.isDirectory }

        val ldPath = libDirs.joinToString(File.pathSeparator) { it.absolutePath }
        Log.i(TAG, "Unpacking ${packs.size} pack file(s) with ${unpack200.absolutePath}")

        for (pack in packs) {
            val out = File(pack.path.removeSuffix(".pack"))
            val pb = ProcessBuilder(unpack200.absolutePath, "-r", pack.absolutePath, out.absolutePath)
                .directory(home)
                .redirectErrorStream(true)
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
            Log.i(TAG, "Unpacked ${pack.name} -> ${out.name} (${out.length()} bytes)")
        }
    }

    private fun findUnpack200(home: File): File? {
        val candidates = listOf(
            File(home, "bin/unpack200"),
            File(home, "jre/bin/unpack200")
        )
        return candidates.firstOrNull { it.isFile }
    }

    private fun findPackFiles(home: File): List<File> {
        if (!home.isDirectory) return emptyList()
        return home.walkTopDown()
            .maxDepth(8)
            .filter { it.isFile && it.name.endsWith(".pack") }
            .toList()
    }
}
