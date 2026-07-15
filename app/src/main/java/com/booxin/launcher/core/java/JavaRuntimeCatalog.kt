package com.booxin.launcher.core.java

/**
 * Built-in Android OpenJDK package catalog.
 *
 * Packages come from PojavLauncherTeam's android-openjdk-build-multiarch releases
 * (Android-compatible JREs, not desktop Adoptium builds).
 *
 * Optional [mirrorPrefix] can rewrite GitHub URLs for restricted networks, e.g.:
 * `https://mirror.ghproxy.com/`
 */
object JavaRuntimeCatalog {

    private const val JRE17_TAG = "jre17-ec28559"
    private const val JRE17_BASE =
        "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/$JRE17_TAG"

    /**
     * JRE8 release assets follow a similar naming style. Tags may evolve;
     * URLs remain centralized here so they can be swapped without touching UI.
     */
    private const val JRE8_TAG = "jre8-32e58b7"
    private const val JRE8_BASE =
        "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/$JRE8_TAG"

    var mirrorPrefix: String = ""

    fun packagesForDevice(abi: JavaAbi = JavaAbi.current()): List<JavaRuntimePackage> {
        return listOfNotNull(
            packageOrNull(
                componentId = "java-8",
                major = 8,
                displayName = "Java 8",
                abi = abi,
                base = JRE8_BASE,
                fileName = "jre8-${abi.packageToken}-release.tar.xz"
            ),
            packageOrNull(
                componentId = "java-17",
                major = 17,
                displayName = "Java 17",
                abi = abi,
                base = JRE17_BASE,
                fileName = when (abi) {
                    JavaAbi.ARM64 -> "jre17-arm64-20210825-release.tar.xz"
                    JavaAbi.ARM32 -> "jre17-arm-20210914-release.tar.xz"
                    JavaAbi.X86 -> "jre17-x86-20220225-release.tar.xz"
                    JavaAbi.X86_64 -> "jre17-x86_64-20210825-release.tar.xz"
                }
            ),
            // Java 21 builds are typically published via CI artifacts; keep a slot
            // so the environment layer can already resolve MC 1.20.5+ requirements.
            packageOrNull(
                componentId = "java-21",
                major = 21,
                displayName = "Java 21",
                abi = abi,
                base = JRE17_BASE.replace(JRE17_TAG, "jre21"),
                fileName = "jre21-${abi.packageToken}-release.tar.xz"
            )
        )
    }

    fun find(majorVersion: Int, abi: JavaAbi = JavaAbi.current()): JavaRuntimePackage? {
        return packagesForDevice(abi).firstOrNull { it.majorVersion == majorVersion }
    }

    fun findByComponentId(componentId: String, abi: JavaAbi = JavaAbi.current()): JavaRuntimePackage? {
        return packagesForDevice(abi).firstOrNull { it.componentId == componentId }
    }

    private fun packageOrNull(
        componentId: String,
        major: Int,
        displayName: String,
        abi: JavaAbi,
        base: String,
        fileName: String
    ): JavaRuntimePackage {
        val raw = "$base/$fileName"
        return JavaRuntimePackage(
            componentId = componentId,
            majorVersion = major,
            displayName = displayName,
            abi = abi,
            downloadUrl = applyMirror(raw),
            fileName = fileName
        )
    }

    private fun applyMirror(url: String): String {
        val prefix = mirrorPrefix.trim()
        if (prefix.isEmpty()) return url
        return if (prefix.endsWith("/")) prefix + url else "$prefix/$url"
    }
}
