package com.booxin.launcher.core.multiplayer

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.data.model.GameVersion
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Creates / reuses isolated launcher versions for room dependency sync.
 * Aligns with PC RoomSessionVersionService (exact fingerprint match only).
 */
object RoomSessionVersionService {
    const val MARKER_FILE_NAME = ".booxin-room-profile.json"

    private val skippedRuntimeFolders = setOf(
        "mods", "config", "saves", "resourcepacks", "screenshots",
        "logs", "shaderpacks", "texturepacks"
    )

    fun computeFingerprint(
        gameVersion: String?,
        loader: String?,
        mods: List<RoomModDependency>
    ): String {
        val versionKey = normalizeKey(gameVersion)
        val loaderKey = normalizeKey(loader)
        val modKeys = mods
            .filter { it.hasDownloadSource }
            .map { toModKey(it) }
            .sorted()
        val payload = buildString {
            append(versionKey).append('\n')
            append(loaderKey).append('\n')
            modKeys.forEach { append(it).append('\n') }
        }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun findExactMatch(
        installedVersions: List<GameVersion>,
        fingerprint: String
    ): GameVersion? {
        if (fingerprint.isBlank()) return null
        for (version in installedVersions) {
            if (!version.installed || version.id.isBlank()) continue
            val marker = tryReadMarker(version.id) ?: continue
            if (marker.fingerprint.equals(fingerprint, ignoreCase = true)) {
                return version
            }
        }
        return null
    }

    fun findCloneSource(
        installedVersions: List<GameVersion>,
        gameVersion: String?,
        loader: String?
    ): GameVersion? {
        val pure = RoomHostDependencyService.extractPureVersion(gameVersion)
        val wantedLoader = RoomHostDependencyService.normalizeLoader(loader)
        return installedVersions
            .asSequence()
            .filter { it.installed && it.id.isNotBlank() }
            .filter { pureVersionMatches(it, pure) }
            .filter { loaderMatches(it, wantedLoader) }
            .firstOrNull()
    }

    fun buildNewInstanceName(
        gameVersion: String?,
        loader: String?,
        fingerprint: String
    ): String {
        var pure = RoomHostDependencyService.extractPureVersion(gameVersion)
        if (pure.isBlank()) pure = "unknown"
        val loaderPart = RoomHostDependencyService.normalizeLoader(loader) ?: "vanilla"
        // Match PC: take first 8 chars when long enough; otherwise keep as-is.
        val shortFp = if (fingerprint.length >= 8) fingerprint.take(8) else fingerprint
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var raw = "联机-$pure-$loaderPart-$shortFp-$stamp"
        raw = RoomHostDependencyService.sanitizeFileName(raw)
        if (raw.length > 96) raw = raw.take(96)
        // PC RunAgentVersionDownloadAsync: if occupied, append -2, -3, …
        return com.booxin.launcher.core.version.VersionInstanceNameGenerator.ensureUnique(raw)
    }

    fun cloneInstanceKeepSource(
        sourceInstanceName: String,
        targetInstanceName: String,
        clientVersion: String
    ): Boolean {
        if (sourceInstanceName.isBlank() ||
            targetInstanceName.isBlank() ||
            clientVersion.isBlank()
        ) {
            return false
        }
        val sourceDirectory = File(LauncherPaths.versionsDir, sourceInstanceName.trim())
        val targetDirectory = File(LauncherPaths.versionsDir, targetInstanceName.trim())
        if (!sourceDirectory.isDirectory || targetDirectory.exists()) return false
        val mainSourceJson = File(sourceDirectory, "${sourceInstanceName.trim()}.json")
        if (!mainSourceJson.isFile) return false

        return try {
            targetDirectory.mkdirs()
            copyVersionDirectory(
                sourceDirectory,
                targetDirectory,
                sourceInstanceName.trim(),
                targetInstanceName.trim()
            )
            if (!rewriteTargetJson(targetDirectory, targetInstanceName.trim(), clientVersion.trim())) {
                targetDirectory.deleteRecursively()
                return false
            }
            clearScopedRuntimeFolders(targetDirectory)
            File(targetDirectory, "mods").mkdirs()
            true
        } catch (_: Throwable) {
            runCatching { targetDirectory.deleteRecursively() }
            false
        }
    }

    fun writeMarker(versionId: String, marker: RoomSessionProfileMarker) {
        val path = markerPath(versionId)
        path.parentFile?.mkdirs()
        val json = JSONObject()
            .put("Fingerprint", marker.fingerprint)
            .put("GameVersion", marker.gameVersion)
            .put("Loader", marker.loader)
            .put("ModCount", marker.modCount)
            .put("CreatedAtUtc", marker.createdAtUtc)
            .toString(2)
        path.writeText(json, StandardCharsets.UTF_8)
    }

    fun tryReadMarker(versionId: String): RoomSessionProfileMarker? {
        val path = markerPath(versionId)
        if (!path.isFile) return null
        return runCatching {
            val o = JSONObject(path.readText(StandardCharsets.UTF_8))
            RoomSessionProfileMarker(
                fingerprint = firstNonBlank(o, "Fingerprint", "fingerprint").orEmpty(),
                gameVersion = firstNonBlank(o, "GameVersion", "gameVersion").orEmpty(),
                loader = firstNonBlank(o, "Loader", "loader"),
                modCount = o.optInt("ModCount", o.optInt("modCount", 0)),
                createdAtUtc = firstNonBlank(o, "CreatedAtUtc", "createdAtUtc").orEmpty()
            )
        }.getOrNull()
    }

    fun detectLoaderForVersion(versionId: String): String? {
        val fromId = RoomHostDependencyService.detectLoaderFromVersionId(versionId)
        if (fromId != null) return fromId
        val jsonFile = File(LauncherPaths.versionsDir, "$versionId/$versionId.json")
        if (!jsonFile.isFile) return null
        return runCatching {
            RoomHostDependencyService.detectLoaderFromJson(JSONObject(jsonFile.readText()))
        }.getOrNull()
    }

    fun resolvePureGameVersion(versionId: String): String {
        val jsonFile = File(LauncherPaths.versionsDir, "$versionId/$versionId.json")
        val root = runCatching {
            if (jsonFile.isFile) JSONObject(jsonFile.readText()) else null
        }.getOrNull()
        return if (root != null) {
            LanServerPropertiesInstaller.resolveGameVersion(versionId, root)
        } else {
            RoomHostDependencyService.extractPureVersion(versionId)
        }
    }

    fun utcNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun markerPath(versionId: String): File =
        File(LauncherPaths.versionsDir, "$versionId/$MARKER_FILE_NAME")

    private fun pureVersionMatches(version: GameVersion, pure: String): Boolean {
        if (pure.isBlank()) return false
        val fromId = RoomHostDependencyService.extractPureVersion(version.id)
        val fromResolved = resolvePureGameVersion(version.id)
        return fromId.equals(pure, ignoreCase = true) ||
            fromResolved.equals(pure, ignoreCase = true)
    }

    private fun loaderMatches(version: GameVersion, wantedLoader: String?): Boolean {
        val actual = detectLoaderForVersion(version.id)
        if (wantedLoader.isNullOrBlank() ||
            wantedLoader.equals("vanilla", ignoreCase = true)
        ) {
            return actual.isNullOrBlank() || actual.equals("vanilla", ignoreCase = true)
        }
        return actual.equals(wantedLoader, ignoreCase = true)
    }

    private fun toModKey(mod: RoomModDependency): String {
        if (!mod.sha1.isNullOrBlank()) {
            return "sha1:" + mod.sha1.trim().lowercase()
        }
        return listOf(
            normalizeKey(mod.source),
            normalizeKey(mod.projectId),
            normalizeKey(mod.versionId),
            normalizeKey(mod.fileName),
            normalizeKey(mod.downloadUrl)
        ).joinToString(":")
    }

    private fun normalizeKey(value: String?): String =
        if (value.isNullOrBlank()) "-" else value.trim().lowercase()

    private fun clearScopedRuntimeFolders(targetDirectory: File) {
        for (folder in skippedRuntimeFolders) {
            val path = File(targetDirectory, folder)
            if (path.isDirectory) {
                runCatching { path.deleteRecursively() }
            }
        }
        val marker = File(targetDirectory, MARKER_FILE_NAME)
        if (marker.isFile) runCatching { marker.delete() }
    }

    private fun copyVersionDirectory(
        sourceDirectory: File,
        targetDirectory: File,
        sourceInstanceName: String,
        targetInstanceName: String
    ) {
        val mainSourceJson = File(sourceDirectory, "$sourceInstanceName.json")
        val mainSourceJar = File(sourceDirectory, "$sourceInstanceName.jar")
        sourceDirectory.walkTopDown().forEach { file ->
            val relative = file.relativeTo(sourceDirectory).path.replace('\\', '/')
            if (relative.isEmpty()) return@forEach
            val root = relative.substringBefore('/')
            if (root in skippedRuntimeFolders) return@forEach
            if (file.isDirectory) {
                File(targetDirectory, relative).mkdirs()
                return@forEach
            }
            val fileName = file.name
            if (fileName.equals(MARKER_FILE_NAME, ignoreCase = true) ||
                fileName.endsWith(".lock", ignoreCase = true) ||
                fileName.endsWith(".tmp", ignoreCase = true) ||
                fileName.endsWith(".log", ignoreCase = true)
            ) {
                return@forEach
            }
            val destination = when {
                file.absolutePath.equals(mainSourceJson.absolutePath, ignoreCase = true) ->
                    File(targetDirectory, "$targetInstanceName.json")
                file.absolutePath.equals(mainSourceJar.absolutePath, ignoreCase = true) ->
                    File(targetDirectory, "$targetInstanceName.jar")
                else -> File(targetDirectory, relative)
            }
            destination.parentFile?.mkdirs()
            file.copyTo(destination, overwrite = true)
        }
    }

    private fun rewriteTargetJson(
        targetDirectory: File,
        targetInstanceName: String,
        clientVersion: String
    ): Boolean {
        val targetJson = File(targetDirectory, "$targetInstanceName.json")
        if (!targetJson.isFile) return false
        return runCatching {
            val root = JSONObject(targetJson.readText(StandardCharsets.UTF_8))
            root.put("id", targetInstanceName)
            // Keep inheritsFrom / libraries from the clone source; only rename profile id.
            targetJson.writeText(root.toString(2), StandardCharsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.optString(k).trim()
            if (v.isNotEmpty() && v != "null") return v
        }
        return null
    }
}
