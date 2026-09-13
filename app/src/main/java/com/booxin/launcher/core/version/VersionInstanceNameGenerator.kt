package com.booxin.launcher.core.version

import com.booxin.launcher.core.LauncherPaths
import java.io.File

/**
 * Folder / VersionId helpers for installs.
 * UI aliases live in [VersionUiMetaStore] — do not use this for display names.
 */
object VersionInstanceNameGenerator {

    fun fabric(mcVersion: String, loaderVersion: String): String =
        "${mcVersion.trim()}-fabric-${loaderVersion.trim()}"

    fun forge(mcVersion: String, loaderVersion: String): String =
        "${mcVersion.trim()}-forge-${loaderVersion.trim()}"

    fun neoForge(mcVersion: String, loaderVersion: String): String =
        "${mcVersion.trim()}-neoforge-${loaderVersion.trim()}"

    fun quilt(mcVersion: String, loaderVersion: String): String =
        "${mcVersion.trim()}-quilt-${loaderVersion.trim()}"

    fun optiFine(mcVersion: String, type: String, patch: String): String =
        "${mcVersion.trim()}-optifine-${type.trim()}_$patch"

    /**
     * PC room name uniqueness: if occupied, append `-2`, `-3`, …
     */
    fun ensureUnique(baseName: String): String {
        val base = baseName.trim().ifBlank { return baseName }
        if (!instanceExists(base)) return base
        for (index in 2 until 100) {
            val alternate = "$base-$index"
            if (!instanceExists(alternate)) return alternate
        }
        return base
    }

    fun instanceExists(instanceName: String): Boolean {
        val id = instanceName.trim()
        if (id.isEmpty()) return false
        val dir = File(LauncherPaths.versionsDir, id)
        return dir.isDirectory || File(dir, "$id.json").isFile
    }
}
