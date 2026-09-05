package com.booxin.launcher.core.download.game

/** Drop desktop LWJGL / platform natives; bump a few known-bad transitive deps. */
object LibraryFilter {

    private val ASM_ALL_5_0_4 = ResolvedLibrary(
        name = "org.ow2.asm:asm-all:5.0.4",
        path = "org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar",
        url = "https://repo1.maven.org/maven2/org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar",
        sha1 = "e6244859997b3d4237a552669279780876228909",
        size = 0L
    )

    private val JNA_5_13 = ResolvedLibrary(
        name = "net.java.dev.jna:jna:5.13.0",
        path = "net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar",
        url = "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar",
        sha1 = "1200e7ebeedbe0d10062093f32925a912020e747",
        size = 0L
    )

    private val JNA_PLATFORM_5_13 = ResolvedLibrary(
        name = "net.java.dev.jna:jna-platform:5.13.0",
        path = "net/java/dev/jna/jna-platform/5.13.0/jna-platform-5.13.0.jar",
        url = "https://repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.13.0/jna-platform-5.13.0.jar",
        sha1 = "88e9a306715e9379f3122415ef4ae759a352640d",
        size = 0L
    )

    private val OSHI_6_3 = ResolvedLibrary(
        name = "com.github.oshi:oshi-core:6.3.0",
        path = "com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar",
        url = "https://repo1.maven.org/maven2/com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar",
        sha1 = "9e98cf55be371cafdb9c70c35d04ec2a8c2b42ac",
        size = 0L
    )

    fun shouldKeep(name: String): Boolean {
        // Desktop LWJGL jars/natives are glibc; Android uses a single patched lwjgl.jar
        // plus jniLibs (.so). Drop all Mojang/Maven org.lwjgl artifacts from the classpath.
        if (name.startsWith("org.lwjgl:") || name.startsWith("org.lwjgl.")) return false
        if (name.contains("jinput-platform") || name.contains("twitch-platform")) return false
        if (name.contains(":natives-") || name.contains("natives-")) return false
        return true
    }

    fun upgrade(libraries: List<ResolvedLibrary>): List<ResolvedLibrary> {
        var sawJnaPlatform = false
        var jnaVersion: String? = null
        var oshiCommonVersion: String? = null
        val upgraded = libraries.map { library ->
            val parts = library.name.split(':')
            val group = parts.getOrNull(0).orEmpty()
            val artifact = parts.getOrNull(1).orEmpty()
            val version = parts.getOrNull(2).orEmpty()
            val versionParts = version.split('.')
            val major = versionParts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = versionParts.getOrNull(1)?.toIntOrNull() ?: 0

            when {
                // Keep ASM 4.x for LaunchWrapper VanillaTweakInjector (ASM4 API).
                // Only bump truly ancient (<4) copies that break on modern tooling.
                artifact == "asm-all" && major < 4 -> ASM_ALL_5_0_4
                group == "net.java.dev.jna" && artifact == "jna" -> {
                    val resolved =
                        if (major > 5 || (major == 5 && minor >= 13)) library else JNA_5_13
                    jnaVersion = resolved.name.split(':').getOrNull(2)
                    resolved
                }
                group == "net.java.dev.jna" && artifact == "jna-platform" -> {
                    sawJnaPlatform = true
                    if (major > 5 || (major == 5 && minor >= 13)) library else JNA_PLATFORM_5_13
                }
                group == "com.github.oshi" && artifact == "oshi-core" -> {
                    // OSHI 7+ splits Quartet into oshi-common; remember to inject it.
                    if (major >= 7) oshiCommonVersion = version
                    // 6.2.x breaks on Android (Udev / Quartet init path).
                    if (major == 6 && minor == 2) OSHI_6_3 else library
                }
                group == "com.github.oshi" && artifact == "oshi-common" -> {
                    oshiCommonVersion = null // already present
                    library
                }
                else -> library
            }
        }

        val withCommon = if (oshiCommonVersion != null) {
            upgraded + oshiCommonLibrary(oshiCommonVersion!!)
        } else {
            upgraded
        }

        val withJnaPlatform = if (!sawJnaPlatform && withCommon.any {
                it.name.startsWith("com.github.oshi:oshi-core:") ||
                    it.name.startsWith("net.java.dev.jna:jna:")
            }
        ) {
            // OSHI needs jna-platform (Udev etc.); some old Forge profiles omit it.
            withCommon + jnaPlatformLibrary(jnaVersion ?: "5.13.0")
        } else {
            withCommon
        }

        // Keep first occurrence per group:artifact:classifier (child/Forge libs are listed first).
        return withJnaPlatform.distinctBy { mavenKey(it.name) }
    }

    /** group:artifact[:classifier] — version stripped for dedupe. */
    fun mavenKey(name: String): String {
        val parts = name.substringBefore('@').split(':')
        return when (parts.size) {
            0, 1, 2 -> name
            3 -> "${parts[0]}:${parts[1]}"
            else -> "${parts[0]}:${parts[1]}:${parts[3]}" // classifier
        }
    }

    private fun oshiCommonLibrary(version: String): ResolvedLibrary {
        val path = "com/github/oshi/oshi-common/$version/oshi-common-$version.jar"
        return ResolvedLibrary(
            name = "com.github.oshi:oshi-common:$version",
            path = path,
            url = "https://repo1.maven.org/maven2/$path",
            sha1 = null,
            size = 0L
        )
    }

    private fun jnaPlatformLibrary(version: String): ResolvedLibrary {
        if (version == "5.13.0") return JNA_PLATFORM_5_13
        val path = "net/java/dev/jna/jna-platform/$version/jna-platform-$version.jar"
        return ResolvedLibrary(
            name = "net.java.dev.jna:jna-platform:$version",
            path = path,
            url = "https://repo1.maven.org/maven2/$path",
            sha1 = null,
            size = 0L
        )
    }
}
