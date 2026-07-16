package com.booxin.launcher.core.java

/**
 * Built-in Android OpenJDK package catalog.
 *
 * Primary source: MojoLauncher rolling `jreN-pojav.zip` packages — the same
 * FCL-compatible split layout (`universal.tar.xz` + `bin-{abi}.tar.xz`) used by
 * FoldCraftLauncher's RuntimeUtils.installJava.
 *
 * Optional [mirrorPrefix] can rewrite GitHub URLs for restricted networks.
 */
object JavaRuntimeCatalog {

    /**
     * Rolling multi-ABI packages from MojoLauncher (Pojav/FCL split format).
     * https://github.com/MojoLauncher/android-openjdk-build-multiarch/releases/tag/rolling
     */
    private const val MOJO_ROLLING_BASE =
        "https://github.com/MojoLauncher/android-openjdk-build-multiarch/releases/download/rolling"

    /**
     * FCL extra Java release (whole-package tar.xz, mainly arm64).
     * Used as fallback when split zip is unavailable for an ABI.
     */
    private const val FCL_JAVA_BASE =
        "https://github.com/FCL-Team/FoldCraftLauncher/releases/download/java"

    private const val AAAAPAI_BASE =
        "https://github.com/aaaapai/android-openjdk-build/releases/download/20260223"

    var mirrorPrefix: String = ""

    fun packagesForDevice(abi: JavaAbi = JavaAbi.current()): List<JavaRuntimePackage> {
        return listOfNotNull(
            splitPackage(8, abi),
            splitPackage(17, abi),
            splitPackage(21, abi),
            splitPackage(25, abi)
        )
    }

    fun find(majorVersion: Int, abi: JavaAbi = JavaAbi.current()): JavaRuntimePackage? {
        return packagesForDevice(abi).firstOrNull { it.majorVersion == majorVersion }
    }

    fun findByComponentId(componentId: String, abi: JavaAbi = JavaAbi.current()): JavaRuntimePackage? {
        return packagesForDevice(abi).firstOrNull { it.componentId == componentId }
    }

    private fun splitPackage(major: Int, abi: JavaAbi): JavaRuntimePackage {
        val fileName = "jre$major-pojav.zip"
        return JavaRuntimePackage(
            componentId = "java-$major",
            majorVersion = major,
            displayName = "Java $major",
            abi = abi,
            downloadUrl = applyMirror("$MOJO_ROLLING_BASE/$fileName"),
            fileName = fileName,
            packageKind = JavaPackageKind.POJAV_SPLIT_ZIP,
            fallbackUrl = fallbackWholePackage(major, abi)?.let { applyMirror(it) }
        )
    }

    private fun fallbackWholePackage(major: Int, abi: JavaAbi): String? {
        if (abi != JavaAbi.ARM64) return null
        return when (major) {
            21 -> "$AAAAPAI_BASE/jre21-arm64-20260223-release.tar.xz"
            25 -> "$FCL_JAVA_BASE/jre25-arm64-20251205-release.tar.xz"
            else -> null
        }
    }

    private fun applyMirror(url: String): String {
        val prefix = mirrorPrefix.trim()
        if (prefix.isEmpty()) return url
        return if (prefix.endsWith("/")) prefix + url else "$prefix/$url"
    }
}
