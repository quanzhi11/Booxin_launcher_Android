package com.booxin.launcher.core

import android.content.Context
import java.io.File

/**
 * Central game storage paths for the launcher.
 * Concrete download / launch logic will plug in later.
 */
object LauncherPaths {
    lateinit var rootDir: File
        private set

    val versionsDir: File get() = File(rootDir, "versions")
    val librariesDir: File get() = File(rootDir, "libraries")
    val assetsDir: File get() = File(rootDir, "assets")
    val accountsFile: File get() = File(rootDir, "accounts.json")

    /** Installed Java runtimes: java/java-8, java/java-17, ... */
    val javaDir: File get() = File(rootDir, "java")
    val javaCacheDir: File get() = File(rootDir, "cache/java")

    fun javaRuntimeDir(componentId: String): File = File(javaDir, componentId)

    fun init(context: Context) {
        rootDir = File(context.filesDir, "minecraft").also { it.mkdirs() }
        versionsDir.mkdirs()
        librariesDir.mkdirs()
        assetsDir.mkdirs()
        File(assetsDir, "indexes").mkdirs()
        File(assetsDir, "objects").mkdirs()
        javaDir.mkdirs()
        javaCacheDir.mkdirs()
    }
}
