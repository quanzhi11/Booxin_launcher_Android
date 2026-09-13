package com.booxin.launcher.core.import

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.booxin.launcher.core.LauncherPaths
import java.io.File
import java.io.IOException

enum class ForeignLauncherKind(
    val packageName: String,
    val displayName: String,
    /** Alternate package ids seen in the wild (queries / install detection). */
    val alternatePackageNames: List<String> = emptyList()
) {
    FCL(
        "com.tungsten.fcl",
        "Fold Craft Launcher (FCL)",
        listOf("com.tungsten.fclauncher")
    ),
    ZL2(
        // Device (ADB): com.movtery.zalithlauncher.v2
        "com.movtery.zalithlauncher.v2",
        "Zalith Launcher 2 (ZL2)",
        listOf(
            "com.movtery.zalithlauncherv2",
            "com.movtery.zalithlauncher2"
        )
    ),
    ZL1(
        "com.movtery.zalithlauncher",
        "Zalith Launcher"
    ),
    MIO(
        "com.miolauncher.app",
        "MioLauncher（澪启动器）",
        listOf(
            "com.mio.launcher",
            "com.mio.launcher.ultimate",
            "com.mio.ultimatelauncher",
            "com.mio.launcher2"
        )
    );

    fun allPackageNames(): List<String> =
        (listOf(packageName) + alternatePackageNames).distinct()
}

data class ForeignMinecraftRoot(
    val kind: ForeignLauncherKind,
    val root: File,
    /** True if the APK is installed (path may still exist after uninstall). */
    val packageInstalled: Boolean,
    /**
     * False when the launcher is installed but Android blocks reading its
     * Android/data folder (common on Android 13+). User must pick a shared path.
     */
    val readable: Boolean = true
)

data class ForeignVersionEntry(
    val id: String,
    val dir: File,
    val hasProfile: Boolean,
    val hasClientJar: Boolean,
    val modsCount: Int,
    val savesCount: Int,
    val resourcePacksCount: Int,
    val shaderPacksCount: Int
) {
    fun summaryLine(): String = buildString {
        append(id)
        val bits = buildList {
            if (modsCount > 0) add("模组 $modsCount")
            if (savesCount > 0) add("存档 $savesCount")
            if (resourcePacksCount > 0) add("资源包 $resourcePacksCount")
            if (shaderPacksCount > 0) add("光影 $shaderPacksCount")
        }
        if (bits.isNotEmpty()) {
            append('\n')
            append(bits.joinToString(" · "))
        }
    }
}

data class ForeignImportProgress(
    val stage: String,
    val current: Int = 0,
    val total: Int = 0,
    val detail: String = ""
)

data class ForeignImportResult(
    val importedIds: List<String>,
    val skippedIds: List<String>,
    val failed: List<Pair<String, String>>
)

/**
 * Detects FCL / ZL2 / ZL1 / MioLauncher by package name + known game roots,
 * then copies selected version folders (and missing shared libraries/assets) into Booxin.
 */
object ForeignLauncherImporter {

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)

    fun detectRoots(context: Context): List<ForeignMinecraftRoot> {
        val sd = Environment.getExternalStorageDirectory()
        val found = linkedMapOf<String, ForeignMinecraftRoot>()
        for (kind in ForeignLauncherKind.entries) {
            val installed = kind.allPackageNames().any { isPackageInstalled(context, it) }
            for (candidate in candidateRoots(sd, kind)) {
                val root = resolveMinecraftRoot(candidate) ?: continue
                val key = root.absolutePath
                if (key in found) continue
                found[key] = ForeignMinecraftRoot(kind, root, installed)
            }
        }
        // Fallback: scan Android/data and /games for zalith / fcl / mio folders.
        probeAndroidDataRoots(sd, found)
        probeGamesDirRoots(sd, found)
        // Installed but Android/data not readable (Android 13+ / OEM lock).
        addInstalledUnreadablePlaceholders(context, sd, found)
        return found.values.toList().sortedWith(
            compareByDescending<ForeignMinecraftRoot> { it.readable }
                .thenByDescending { it.packageInstalled }
                .thenBy { it.kind.ordinal }
                .thenBy { it.root.absolutePath }
        )
    }

    fun listVersions(root: ForeignMinecraftRoot): List<ForeignVersionEntry> {
        val versionsDir = File(root.root, "versions")
        if (!versionsDir.isDirectory) return emptyList()
        return versionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val id = dir.name.trim()
                if (id.isEmpty() || id.startsWith('.')) return@mapNotNull null
                val json = File(dir, "$id.json")
                val jar = File(dir, "$id.jar")
                val anyJson = dir.listFiles()?.any { it.isFile && it.name.endsWith(".json") } == true
                if (!json.isFile && !jar.isFile && !anyJson) return@mapNotNull null
                ForeignVersionEntry(
                    id = id,
                    dir = dir,
                    hasProfile = json.isFile || anyJson,
                    hasClientJar = jar.isFile,
                    modsCount = countChildren(File(dir, "mods")),
                    savesCount = countChildren(File(dir, "saves")),
                    resourcePacksCount = countChildren(File(dir, "resourcepacks")),
                    shaderPacksCount = countChildren(File(dir, "shaderpacks"))
                )
            }
            ?.sortedByDescending { it.id.lowercase() }
            .orEmpty()
    }

    suspend fun importVersions(
        root: ForeignMinecraftRoot,
        entries: List<ForeignVersionEntry>,
        onProgress: (ForeignImportProgress) -> Unit = {}
    ): ForeignImportResult {
        if (!LauncherPaths.isInitialized) error("LauncherPaths 未初始化")
        val destVersions = LauncherPaths.versionsDir.also { it.mkdirs() }
        val imported = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        val total = entries.size.coerceAtLeast(1)
        entries.forEachIndexed { index, entry ->
            onProgress(
                ForeignImportProgress(
                    stage = "正在导入版本",
                    current = index + 1,
                    total = total,
                    detail = entry.id
                )
            )
            try {
                val targetId = allocateUniqueId(destVersions, entry.id)
                val dest = File(destVersions, targetId)
                if (dest.exists()) {
                    skipped += entry.id
                    return@forEachIndexed
                }
                copyDirectory(entry.dir, dest) { copied, fileTotal, name ->
                    onProgress(
                        ForeignImportProgress(
                            stage = "复制 ${entry.id}",
                            current = copied,
                            total = fileTotal.coerceAtLeast(1),
                            detail = name
                        )
                    )
                }
                renameProfileIfNeeded(dest, entry.id, targetId)
                stripForeignLaunchSettings(dest)
                disableImportedForgeEarlyWindow(dest)
                if (entry.id.contains("optifine", ignoreCase = true) ||
                    targetId.contains("optifine", ignoreCase = true)
                ) {
                    sanitizeImportedOptiFine(dest)
                }
                mergeInstanceExtras(root.root, dest, onProgress)
                imported += targetId
            } catch (t: Throwable) {
                failed += entry.id to (t.message ?: t.javaClass.simpleName)
            }
        }
        onProgress(ForeignImportProgress(stage = "同步公共库…", detail = "libraries / assets"))
        runCatching { mergeShared(root.root, LauncherPaths.librariesDir, "libraries", onProgress) }
        runCatching { mergeShared(root.root, LauncherPaths.assetsDir, "assets", onProgress) }
        onProgress(ForeignImportProgress(stage = "完成", current = total, total = total))
        return ForeignImportResult(imported, skipped, failed)
    }

    private fun candidateRoots(sd: File, kind: ForeignLauncherKind): List<File> {
        val pkgs = kind.allPackageNames()
        return when (kind) {
            ForeignLauncherKind.FCL -> buildList {
                add(File(sd, "FCL/.minecraft"))
                for (pkg in pkgs) {
                    add(File(sd, "Android/data/$pkg/files/.minecraft"))
                    add(File(sd, "Android/data/$pkg/files/FCL/.minecraft"))
                }
            }
            ForeignLauncherKind.ZL2 -> buildList {
                add(File(sd, "games/zalithlauncher"))
                add(File(sd, "games/ZalithLauncher"))
                add(File(sd, "games/zalithlauncher2"))
                add(File(sd, "games/zalithlauncherv2"))
                add(File(sd, "games/ZalithLauncher2"))
                add(File(sd, "games/ZalithLauncherV2"))
                add(File(sd, "games/zalithlauncher/.minecraft"))
                add(File(sd, "games/ZalithLauncher/.minecraft"))
                add(File(sd, "games/zalithlauncher2/.minecraft"))
                add(File(sd, "games/zalithlauncherv2/.minecraft"))
                for (pkg in pkgs) {
                    add(File(sd, "Android/data/$pkg/files/.minecraft"))
                    add(File(sd, "Android/data/$pkg/files/games/zalithlauncher"))
                    add(File(sd, "Android/data/$pkg/files/games/zalithlauncher/.minecraft"))
                    add(File(sd, "Android/data/$pkg/files/games/zalithlauncherv2"))
                    add(File(sd, "Android/data/$pkg/files/games/zalithlauncherv2/.minecraft"))
                    add(File(sd, "Android/data/$pkg/files/games/ZalithLauncher"))
                    add(File(sd, "Android/data/$pkg/files/games/zalithlauncher2"))
                }
            }
            ForeignLauncherKind.ZL1 -> buildList {
                add(File(sd, "games/zalithlauncher"))
                for (pkg in pkgs) {
                    add(File(sd, "Android/data/$pkg/files/.minecraft"))
                    add(File(sd, "Android/data/$pkg/files/games/zalithlauncher"))
                    add(File(sd, "Android/data/$pkg/files/games/zalithlauncher/.minecraft"))
                }
            }
            ForeignLauncherKind.MIO -> buildList {
                add(File(sd, "MioLauncher/.minecraft"))
                add(File(sd, "MioLauncher"))
                add(File(sd, "miolauncher/.minecraft"))
                add(File(sd, "miolauncher"))
                add(File(sd, "mio/.minecraft"))
                add(File(sd, "Mio/.minecraft"))
                add(File(sd, "games/MioLauncher/.minecraft"))
                add(File(sd, "games/MioLauncher"))
                add(File(sd, "games/miolauncher/.minecraft"))
                for (pkg in pkgs) {
                    add(File(sd, "Android/data/$pkg/files/.minecraft"))
                    add(File(sd, "Android/data/$pkg/files/MioLauncher/.minecraft"))
                    add(File(sd, "Android/data/$pkg/files/MioLauncher"))
                    add(File(sd, "Android/data/$pkg/files/miolauncher/.minecraft"))
                    add(File(sd, "Android/data/$pkg/files/games/.minecraft"))
                }
            }
        }
    }

    private fun probeAndroidDataRoots(
        sd: File,
        found: MutableMap<String, ForeignMinecraftRoot>
    ) {
        val androidData = File(sd, "Android/data")
        if (!androidData.isDirectory) return
        val dirs = androidData.listFiles()?.filter { it.isDirectory } ?: return
        for (pkgDir in dirs) {
            val name = pkgDir.name.lowercase()
            val kind = when {
                name.contains("zalithlauncherv2") ||
                    name.contains("zalithlauncher.v2") ||
                    name.contains("zalithlauncher2") -> ForeignLauncherKind.ZL2
                name.contains("zalithlauncher") -> ForeignLauncherKind.ZL1
                name.contains("tungsten.fcl") -> ForeignLauncherKind.FCL
                name.contains("miolauncher") ||
                    name.startsWith("com.mio.") ||
                    name.contains("mio.launcher") -> ForeignLauncherKind.MIO
                else -> continue
            }
            val candidates = listOf(
                File(pkgDir, "files/.minecraft"),
                File(pkgDir, "files/games/zalithlauncher"),
                File(pkgDir, "files/games/zalithlauncher/.minecraft"),
                File(pkgDir, "files/games/zalithlauncherv2"),
                File(pkgDir, "files/games/zalithlauncherv2/.minecraft"),
                File(pkgDir, "files/FCL/.minecraft"),
                File(pkgDir, "files/MioLauncher/.minecraft"),
                File(pkgDir, "files/MioLauncher"),
                File(pkgDir, "files/miolauncher/.minecraft"),
                File(pkgDir, "files/games/.minecraft")
            )
            val installed = kind.allPackageNames().any { it.equals(pkgDir.name, ignoreCase = true) } ||
                name.contains("zalith") || name.contains("fcl") || name.contains("mio")
            for (candidate in candidates) {
                val root = resolveMinecraftRoot(candidate) ?: continue
                val key = root.absolutePath
                if (key in found) continue
                found[key] = ForeignMinecraftRoot(kind, root, installed)
            }
        }
    }

    private fun probeGamesDirRoots(
        sd: File,
        found: MutableMap<String, ForeignMinecraftRoot>
    ) {
        val games = File(sd, "games")
        if (!games.isDirectory) return
        val dirs = games.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in dirs) {
            val name = dir.name.lowercase()
            val kind = when {
                name.contains("zalith") && (name.contains("v2") || name.contains("2")) ->
                    ForeignLauncherKind.ZL2
                name.contains("zalith") -> ForeignLauncherKind.ZL1
                name.contains("mio") -> ForeignLauncherKind.MIO
                name.contains("fcl") -> ForeignLauncherKind.FCL
                else -> continue
            }
            val candidates = listOf(dir, File(dir, ".minecraft"))
            for (candidate in candidates) {
                val root = resolveMinecraftRoot(candidate) ?: continue
                val key = root.absolutePath
                if (key in found) continue
                found[key] = ForeignMinecraftRoot(kind, root, packageInstalled = true)
            }
        }
    }

    /**
     * Android 13+ often blocks reading other apps' Android/data even with
     * MANAGE_EXTERNAL_STORAGE. Still list installed launchers so the UI can
     * ask the user to pick a shared .minecraft folder.
     */
    private fun addInstalledUnreadablePlaceholders(
        context: Context,
        sd: File,
        found: MutableMap<String, ForeignMinecraftRoot>
    ) {
        for (kind in ForeignLauncherKind.entries) {
            val installed = kind.allPackageNames().any { isPackageInstalled(context, it) }
            if (!installed) continue
            if (found.values.any { it.kind == kind && it.readable }) continue
            val pkg = kind.allPackageNames().firstOrNull { isPackageInstalled(context, it) }
                ?: kind.packageName
            val expected = File(sd, "Android/data/$pkg/files/.minecraft")
            // Prefer showing the expected private path even if unreadable.
            val key = "unreadable:${kind.name}:$pkg"
            if (key in found) continue
            found[key] = ForeignMinecraftRoot(
                kind = kind,
                root = expected,
                packageInstalled = true,
                readable = false
            )
        }
    }

    private fun resolveMinecraftRoot(candidate: File): File? {
        if (!candidate.exists()) return null
        // listFiles() may be null when Android blocks Android/data access.
        val versions = File(candidate, "versions")
        if (versions.isDirectory) {
            val kids = versions.listFiles()
            // If we cannot list children, treat as unreadable (not a valid root).
            if (kids == null) return null
            return candidate
        }
        val nested = File(candidate, ".minecraft")
        if (File(nested, "versions").isDirectory) {
            if (File(nested, "versions").listFiles() == null) return null
            return nested
        }
        return null
    }

    private fun countChildren(dir: File): Int {
        if (!dir.isDirectory) return 0
        return dir.listFiles()?.count { !it.name.startsWith('.') } ?: 0
    }

    private fun allocateUniqueId(versionsDir: File, preferred: String): String {
        val base = preferred.trim().ifBlank { "imported" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
        var id = base
        var n = 2
        while (File(versionsDir, id).exists()) {
            id = "$base-imported${if (n == 2) "" else "-$n"}"
            n++
        }
        return id
    }

    private fun renameProfileIfNeeded(dest: File, fromId: String, toId: String) {
        if (fromId == toId) return
        val oldJson = File(dest, "$fromId.json")
        val oldJar = File(dest, "$fromId.jar")
        val newJson = File(dest, "$toId.json")
        val newJar = File(dest, "$toId.jar")
        if (oldJson.isFile && !newJson.exists()) oldJson.renameTo(newJson)
        if (oldJar.isFile && !newJar.exists()) oldJar.renameTo(newJar)
    }

    private fun stripForeignLaunchSettings(destVersion: File) {
        val drop = listOf(
            "options.txt",
            "optionsof.txt",
            "optionsshaders.txt",
            "optionsscreens.txt",
            "servers.dat_old"
        )
        for (name in drop) {
            File(destVersion, name).takeIf { it.isFile }?.delete()
        }
    }

    private fun disableImportedForgeEarlyWindow(destVersion: File) {
        val config = File(destVersion, "config")
        if (!config.exists()) config.mkdirs()
        runCatching {
            File(config, "splash.properties").writeText("enabled=false")
        }
        val fmlToml = File(config, "fml.toml")
        val existing = if (fmlToml.isFile) fmlToml.readText() else ""
        val key = Regex("""(?m)^\s*earlyWindowControl\s*=\s*\S+""")
        val updated = when {
            key.containsMatchIn(existing) ->
                key.replace(existing, "earlyWindowControl=false")
            existing.isBlank() ->
                "earlyWindowControl=false\n"
            else ->
                existing.trimEnd() + "\nearlyWindowControl=false\n"
        }
        runCatching { fmlToml.writeText(updated) }
    }

    private fun sanitizeImportedOptiFine(destVersion: File) {
        File(destVersion, "optionsshaders.txt").writeText("shaderPack=OFF\n")
        runCatching {
            File(destVersion, ".booxin_optifine_shaders_off_v1").writeText("import")
        }
    }

    private fun mergeInstanceExtras(
        sourceRoot: File,
        destVersion: File,
        onProgress: (ForeignImportProgress) -> Unit
    ) {
        val folders = listOf("resourcepacks", "saves")
        for (name in folders) {
            val src = File(sourceRoot, name)
            if (!src.isDirectory) continue
            val dest = File(destVersion, name)
            val destEmpty = !dest.isDirectory ||
                (dest.listFiles()?.none { !it.name.startsWith('.') } != false)
            if (!destEmpty) continue
            onProgress(ForeignImportProgress(stage = "合并公共 $name", detail = destVersion.name))
            runCatching {
                copyDirectory(src, dest) { _, _, _ -> }
            }
        }
    }

    private fun mergeShared(
        sourceRoot: File,
        destRoot: File,
        label: String,
        onProgress: (ForeignImportProgress) -> Unit
    ) {
        val src = File(sourceRoot, label)
        if (!src.isDirectory) return
        destRoot.mkdirs()
        var copied = 0
        src.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val rel = file.relativeTo(src).path
            val out = File(destRoot, rel)
            if (out.isFile && out.length() >= file.length() && out.length() > 0L) return@forEach
            out.parentFile?.mkdirs()
            file.copyTo(out, overwrite = true)
            copied++
            if (copied % 25 == 0) {
                onProgress(
                    ForeignImportProgress(
                        stage = "同步 $label",
                        current = copied,
                        detail = rel
                    )
                )
            }
        }
    }

    private fun copyDirectory(
        src: File,
        dst: File,
        onFile: (copied: Int, total: Int, name: String) -> Unit
    ) {
        if (!src.isDirectory) throw IOException("源目录不存在：${src.absolutePath}")
        val files = src.walkTopDown().filter { it.isFile }.filter { !shouldSkipImportRelative(it, src) }.toList()
        val total = files.size.coerceAtLeast(1)
        files.forEachIndexed { index, file ->
            val rel = file.relativeTo(src).path
            val out = File(dst, rel)
            out.parentFile?.mkdirs()
            file.copyTo(out, overwrite = true)
            onFile(index + 1, total, rel)
        }
        if (files.isEmpty()) {
            dst.mkdirs()
        }
    }

    private fun shouldSkipImportRelative(file: File, root: File): Boolean {
        val name = file.name.lowercase()
        if (name == "options.txt" ||
            name == "optionsof.txt" ||
            name == "optionsshaders.txt" ||
            name == "optionsscreens.txt"
        ) {
            return true
        }
        val rel = file.relativeTo(root).path.replace('\\', '/')
        if (rel.startsWith("crash-reports/") ||
            rel.startsWith("logs/") ||
            rel.startsWith("natives/")
        ) {
            return true
        }
        return false
    }
}
