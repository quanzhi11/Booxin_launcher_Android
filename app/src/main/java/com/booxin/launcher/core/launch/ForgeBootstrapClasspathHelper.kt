package com.booxin.launcher.core.launch

import android.util.Log
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.ResolvedLibrary
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Curated classpath for modern ForgeBootstrap
 * (`net.minecraftforge.bootstrap.ForgeBootstrap`, Forge 1.20.3+ / 1.21.x / 26.x).
 *
 * Ported from FoldCraftLauncher `ForgeBootstrapClasspathHelper` (reference only —
 * this app is **not** FCL). Dumping every library onto `-cp` breaks the JPMS boot
 * layer and surfaces `Optional.get(): No value present` in `Bootstrap.start`
 * (seen on Huawei tablets).
 */
object ForgeBootstrapClasspathHelper {
    private const val TAG = "ForgeBootstrap"

    fun isForgeBootstrapMainClass(mainClass: String?): Boolean =
        mainClass != null && mainClass.lowercase(Locale.ROOT).contains("forgebootstrap")

    /**
     * Parent of `libraries/` — ForgeBootstrap resolves `libraries/` relative to process cwd.
     * Game directory must still be passed via `--gameDir`.
     */
    fun resolveLibrariesRoot(): File {
        val librariesDir = LauncherPaths.librariesDir
        return librariesDir.parentFile ?: librariesDir
    }

    /**
     * @return ordered classpath jars, or null if shim/client jars are missing
     */
    fun buildClasspath(
        libraries: List<ResolvedLibrary>,
        versionId: String
    ): LinkedHashSet<File>? {
        val librariesDir = LauncherPaths.librariesDir
        val shimJar = resolveShimJar(libraries, librariesDir, versionId)
        if (shimJar == null || !shimJar.isFile) {
            Log.e(TAG, "Missing forge-*-shim.jar; reinstall Forge.")
            return null
        }
        val clientJar = resolveForgeClientJar(libraries, librariesDir)
        if (clientJar == null || !clientJar.isFile || clientJar.length() < 1_000_000L) {
            Log.e(TAG, "Missing or incomplete forge-*-client.jar; reinstall Forge.")
            return null
        }

        val shimLines = readBootstrapShimList(shimJar)
        val shimCoordinateKeys = parseShimCoordinateKeys(shimLines)

        val paths = LinkedHashSet<File>()
        val seenIdentities = HashSet<String>()

        tryAdd(paths, seenIdentities, clientJar)

        for (artifactPath in parseShimArtifactPaths(shimLines)) {
            val local = File(librariesDir, artifactPath.replace('/', File.separatorChar))
            if (!local.isFile) {
                Log.w(TAG, "shim library missing: $artifactPath")
                continue
            }
            tryAdd(paths, seenIdentities, local)
        }

        for (library in libraries) {
            val path = library.path
            val lowerPath = path.lowercase(Locale.ROOT)
            if (lowerPath.contains("-client.jar")) continue
            val group = library.name.substringBefore(':')
            // Shim list already carries the Forge/modlauncher stack.
            if (group == "net.minecraftforge" || group == "cpw.mods") continue
            val file = File(librariesDir, path)
            if (!file.isFile) continue
            val parts = library.name.substringBefore('@').split(':')
            if (parts.size < 2) continue
            val coordinate = "${parts[0]}:${parts[1]}".lowercase(Locale.ROOT)
            if (coordinate in shimCoordinateKeys) continue
            tryAdd(paths, seenIdentities, file)
        }

        resolveCoremodsClasspath(librariesDir, paths, seenIdentities)
        Log.i(TAG, "curated classpath size=${paths.size} shim=${shimJar.name} client=${clientJar.name}")
        return paths
    }

    private fun tryAdd(paths: LinkedHashSet<File>, seen: HashSet<String>, file: File) {
        val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
        val identity = identityKey(canonical)
        if (!seen.add(identity)) return
        paths.add(canonical)
    }

    private fun identityKey(file: File): String =
        file.name.lowercase(Locale.ROOT) + "|" + file.length()

    private fun resolveForgeClientJar(libraries: List<ResolvedLibrary>, librariesDir: File): File? {
        for (library in libraries) {
            val path = library.path
            if (!path.lowercase(Locale.ROOT).contains("-client.jar")) continue
            val file = File(librariesDir, path)
            if (file.isFile) return file
        }
        return null
    }

    private fun resolveShimJar(
        libraries: List<ResolvedLibrary>,
        librariesDir: File,
        versionId: String
    ): File? {
        for (library in libraries) {
            val path = library.path
            val lower = path.lowercase(Locale.ROOT)
            if (!lower.contains("forge")) continue
            if (!lower.endsWith("-client.jar") && !lower.endsWith("-universal.jar")) continue
            val shimRel = path
                .replace("-client.jar", "-shim.jar")
                .replace("-universal.jar", "-shim.jar")
            val shim = File(librariesDir, shimRel.replace('/', File.separatorChar))
            if (shim.isFile) return shim
        }

        val forgeRoot = File(librariesDir, "net/minecraftforge/forge".replace('/', File.separatorChar))
        if (!forgeRoot.isDirectory) return null
        val versionIdLower = versionId.lowercase(Locale.ROOT)
        val versionDirs = forgeRoot.listFiles { f -> f.isDirectory } ?: return null
        var best: File? = null
        for (dir in versionDirs) {
            val shims = dir.listFiles { _, name -> name.endsWith("-shim.jar") } ?: continue
            for (shim in shims) {
                val name = shim.name.lowercase(Locale.ROOT)
                if (versionIdLower.contains("forge") &&
                    name.contains(dir.name.lowercase(Locale.ROOT))
                ) {
                    return shim
                }
                if (best == null || shim.lastModified() > best.lastModified()) {
                    best = shim
                }
            }
        }
        return best
    }

    private fun readBootstrapShimList(shimJar: File): List<String> {
        val lines = ArrayList<String>()
        try {
            ZipFile(shimJar).use { zip ->
                val entry = zip.getEntry("bootstrap-shim.list") ?: return lines
                BufferedReader(
                    InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)
                ).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) lines += line
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read bootstrap-shim.list", t)
        }
        return lines
    }

    private fun parseShimCoordinateKeys(shimLines: List<String>): Set<String> {
        val keys = HashSet<String>()
        for (line in shimLines) {
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val key = normalizeCoordinateKey(parts[1].trim()) ?: continue
            keys += key.lowercase(Locale.ROOT)
        }
        return keys
    }

    private fun parseShimArtifactPaths(shimLines: List<String>): List<String> {
        val paths = ArrayList<String>()
        for (line in shimLines) {
            val parts = line.split('\t')
            if (parts.size >= 3 && parts[2].isNotBlank()) {
                paths += parts[2].trim()
            }
        }
        return paths
    }

    private fun normalizeCoordinateKey(coordinate: String): String? {
        if (coordinate.isBlank()) return null
        val parts = coordinate.split(':')
        if (parts.size < 2) return null
        return "${parts[0]}:${parts[1]}"
    }

    private fun resolveCoremodsClasspath(
        librariesDir: File,
        paths: LinkedHashSet<File>,
        seenIdentities: HashSet<String>
    ) {
        val snapshot = paths.map { it.absolutePath }
        val hasImplementation = snapshot.any { isCoremodsImplementationJar(it) }
        val apiJars = paths.filter { isCoremodsApiJar(it.absolutePath) }

        if (hasImplementation && apiJars.isNotEmpty()) {
            paths.removeAll(apiJars.toSet())
            rebuildIdentities(paths, seenIdentities)
        } else if (apiJars.isNotEmpty()) {
            for (apiJar in apiJars) {
                val version = tryParseCoremodsApiVersion(apiJar.name) ?: continue
                val impl = File(
                    librariesDir,
                    ("net/minecraftforge/coremods/$version/coremods-$version.jar")
                        .replace('/', File.separatorChar)
                )
                if (!impl.isFile) {
                    Log.w(TAG, "coremods implementation missing: ${impl.absolutePath}")
                    continue
                }
                paths.remove(apiJar)
                tryAdd(paths, seenIdentities, impl)
            }
        }

        if (paths.any { isCoremodsImplementationJar(it.absolutePath) }) {
            ensureNashornOnClasspath(librariesDir, paths, seenIdentities)
        }
    }

    private fun rebuildIdentities(paths: LinkedHashSet<File>, seen: HashSet<String>) {
        seen.clear()
        for (file in paths) {
            seen += identityKey(file)
        }
    }

    private fun ensureNashornOnClasspath(
        librariesDir: File,
        paths: LinkedHashSet<File>,
        seen: HashSet<String>
    ) {
        if (paths.any { isNashornCoreJar(it.absolutePath) }) return
        val preferred = File(
            librariesDir,
            "org/openjdk/nashorn/nashorn-core/15.4/nashorn-core-15.4.jar"
                .replace('/', File.separatorChar)
        )
        if (preferred.isFile) {
            tryAdd(paths, seen, preferred)
            return
        }
        val root = File(librariesDir, "org/openjdk/nashorn/nashorn-core".replace('/', File.separatorChar))
        if (!root.isDirectory) {
            Log.w(TAG, "nashorn-core missing; coremods may fail")
            return
        }
        try {
            val best = Files.walk(root.toPath()).use { stream ->
                stream
                    .filter {
                        val n = it.fileName.toString()
                        n.startsWith("nashorn-core-") && n.endsWith(".jar")
                    }
                    .map { it.toFile() }
                    .filter { it.length() > 1024 }
                    .max { a, b -> a.lastModified().compareTo(b.lastModified()) }
                    .orElse(null)
            }
            if (best != null) tryAdd(paths, seen, best)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to locate nashorn-core", t)
        }
    }

    private fun isCoremodsApiJar(path: String): Boolean {
        val name = File(path).name.lowercase(Locale.ROOT)
        val lower = path.replace('\\', '/').lowercase(Locale.ROOT)
        return lower.contains("/coremods-api/") || name.startsWith("coremods-api-")
    }

    private fun isCoremodsImplementationJar(path: String): Boolean {
        val name = File(path).name.lowercase(Locale.ROOT)
        val lower = path.replace('\\', '/').lowercase(Locale.ROOT)
        return lower.contains("/coremods/") &&
            !isCoremodsApiJar(path) &&
            name.startsWith("coremods-") &&
            name.endsWith(".jar")
    }

    private fun isNashornCoreJar(path: String): Boolean {
        val name = File(path).name.lowercase(Locale.ROOT)
        return name.startsWith("nashorn-core-") && name.endsWith(".jar")
    }

    private fun tryParseCoremodsApiVersion(name: String): String? {
        val prefix = "coremods-api-"
        if (!name.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true) ||
            !name.endsWith(".jar")
        ) {
            return null
        }
        return name.substring(prefix.length, name.length - 4)
    }
}
