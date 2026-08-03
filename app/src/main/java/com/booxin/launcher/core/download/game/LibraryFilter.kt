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
        return libraries.map { library ->
            val parts = library.name.split(':')
            val artifact = parts.getOrNull(1).orEmpty()
            val versionParts = parts.getOrNull(2)?.split('.').orEmpty()
            val major = versionParts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = versionParts.getOrNull(1)?.toIntOrNull() ?: 0

            when {
                artifact == "asm-all" && major < 5 -> ASM_ALL_5_0_4
                library.name.startsWith("net.java.dev.jna:jna:") -> {
                    if (major >= 5 && minor >= 13) library else JNA_5_13
                }
                library.name.startsWith("com.github.oshi:oshi-core:") -> {
                    if (major == 6 && minor == 2) OSHI_6_3 else library
                }
                else -> library
            }
        }.distinctBy { it.name }
    }
}
