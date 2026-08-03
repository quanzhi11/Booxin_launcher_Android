package com.booxin.launcher.core.download.modloader

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.java.EmbeddedJavaRunner
import com.booxin.launcher.core.java.InstalledJavaRuntime
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

object ForgeInstallerRunner {
    private const val FORGE_INSTALLER_MAIN = "com.bangbang93.ForgeInstaller"

    fun detectInstallerKind(installerJar: File): ForgeInstallerKind {
        if (!installerJar.isFile) return ForgeInstallerKind.BOOTSTRAP_INJECTOR
        return runCatching {
            ZipFile(installerJar).use { zip ->
                val entry = zip.getEntry("install_profile.json")
                    ?: return@use ForgeInstallerKind.BOOTSTRAP_INJECTOR
                val profile = JSONObject(zip.getInputStream(entry).bufferedReader().readText())
                when {
                    profile.has("spec") -> ForgeInstallerKind.NEW_SPEC
                    profile.has("install") && profile.has("versionInfo") -> ForgeInstallerKind.OLD_LEGACY
                    else -> ForgeInstallerKind.BOOTSTRAP_INJECTOR
                }
            }
        }.getOrDefault(ForgeInstallerKind.BOOTSTRAP_INJECTOR)
    }

    fun runModernInstaller(
        java: InstalledJavaRuntime,
        installerJar: File,
        injectorJar: File,
        minecraftRoot: File = LauncherPaths.rootDir
    ): Result<String> = runCatching {
        prepareLauncherProfiles(minecraftRoot)
        val classpath = listOf(injectorJar.absolutePath, installerJar.absolutePath)
            .joinToString(File.pathSeparator)
        val command = listOf(
            "-cp",
            classpath,
            FORGE_INSTALLER_MAIN,
            minecraftRoot.absolutePath
        )
        val exitCode = EmbeddedJavaRunner.run(java, minecraftRoot, command)
        if (exitCode != 0) {
            throw IllegalStateException("Forge 安装器退出码 $exitCode")
        }
        "ok"
    }

    fun installLegacy(
        installerJar: File,
        targetVersionId: String,
        minecraftRoot: File = LauncherPaths.rootDir
    ): Result<Unit> = runCatching {
        ZipFile(installerJar).use { zip ->
            val profileEntry = zip.getEntry("install_profile.json")
                ?: error("Legacy Forge 安装包缺少 install_profile.json")
            val profileText = zip.getInputStream(profileEntry).bufferedReader().readText()
            val profile = JSONObject(profileText)
            val versionFolder = File(LauncherPaths.versionsDir, targetVersionId).also { it.mkdirs() }

            if (!profile.has("install")) {
                val jsonPath = profile.getString("json").trimStart('/')
                val versionEntry = zip.getEntry(jsonPath)
                    ?: error("安装包缺少版本 JSON: $jsonPath")
                val versionJson = JSONObject(
                    zip.getInputStream(versionEntry).bufferedReader().readText()
                )
                versionJson.put("id", targetVersionId)
                File(versionFolder, "$targetVersionId.json").writeText(versionJson.toString(2))

                val extractRoot = File(minecraftRoot, "cache/forge-legacy-${System.currentTimeMillis()}")
                try {
                    extractZip(installerJar, extractRoot)
                    val mavenSource = File(extractRoot, "maven")
                    if (mavenSource.isDirectory) {
                        copyTree(mavenSource, LauncherPaths.librariesDir)
                    }
                } finally {
                    extractRoot.deleteRecursively()
                }
            } else {
                val versionInfo = profile.getJSONObject("install")
                val jsonName = versionInfo.getString("json")
                val versionEntry = zip.getEntry(jsonName.trimStart('/'))
                    ?: zip.getEntry(jsonName)
                    ?: error("安装包缺少版本 JSON: $jsonName")
                val versionJson = JSONObject(
                    zip.getInputStream(versionEntry).bufferedReader().readText()
                )
                versionJson.put("id", targetVersionId)
                File(versionFolder, "$targetVersionId.json").writeText(versionJson.toString(2))

                val pathKey = versionInfo.keys().asSequence().firstOrNull {
                    it.equals("path", ignoreCase = true) || it.equals("filePath", ignoreCase = true)
                }
                if (pathKey != null) {
                    val entryName = versionInfo.getString(pathKey).trimStart('/')
                    val libEntry = zip.getEntry(entryName) ?: error("安装包缺少库: $entryName")
                    val target = File(LauncherPaths.librariesDir, entryName)
                    target.parentFile?.mkdirs()
                    zip.getInputStream(libEntry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }

                val extractRoot = File(minecraftRoot, "cache/forge-legacy-${System.currentTimeMillis()}")
                try {
                    extractZip(installerJar, extractRoot)
                    val mavenSource = File(extractRoot, "maven")
                    if (mavenSource.isDirectory) {
                        copyTree(mavenSource, LauncherPaths.librariesDir)
                    }
                } finally {
                    extractRoot.deleteRecursively()
                }
            }
        }
    }

    private fun prepareLauncherProfiles(minecraftRoot: File) {
        val profiles = File(minecraftRoot, "launcher_profiles.json")
        if (profiles.isFile) return
        profiles.writeText(
            """
            {
              "profiles": {},
              "selectedProfile": "",
              "clientToken": "booxin",
              "authenticationDatabase": {},
              "launcherVersion": { "name": "booxin", "format": 21 }
            }
            """.trimIndent()
        )
    }

    private fun extractZip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val out = File(destDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun copyTree(source: File, dest: File) {
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source)
            val target = File(dest, relative.path)
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }
    }
}
