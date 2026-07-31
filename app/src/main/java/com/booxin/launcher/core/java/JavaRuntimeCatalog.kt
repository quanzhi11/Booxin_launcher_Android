package com.booxin.launcher.core.java

/**
 * Built-in Android OpenJDK package catalog.
 *
 * Primary: Coze share links (CN-friendly CDN, redirect to jreN-pojav.zip).
 * Fallback: MojoLauncher GitHub rolling + optional arm64 whole packages.
 */
object JavaRuntimeCatalog {

    /**
     * Rolling multi-ABI packages from MojoLauncher (Pojav/FCL split format).
     * https://github.com/MojoLauncher/android-openjdk-build-multiarch/releases/tag/rolling
     */
    private const val MOJO_ROLLING_BASE =
        "https://github.com/MojoLauncher/android-openjdk-build-multiarch/releases/download/rolling"

    private const val FCL_JAVA_BASE =
        "https://github.com/FCL-Team/FoldCraftLauncher/releases/download/java"

    private const val AAAAPAI_BASE =
        "https://github.com/aaaapai/android-openjdk-build/releases/download/20260223"

    /** Coze short links — resolve to signed static.coze.site zip each request. */
    private val COZE_PRIMARY = mapOf(
        8 to "https://www.coze.cn/s/hMacSNHSgAg/",
        17 to "https://www.coze.cn/s/iTHub6HrQW0/",
        21 to "https://www.coze.cn/s/iISPQC41rdI/",
        25 to "https://www.coze.cn/s/hFPcsyBdgDU/"
    )

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
        val urls = buildList {
            COZE_PRIMARY[major]?.let { add(it) }
            add(applyMirror("$MOJO_ROLLING_BASE/$fileName"))
            fallbackWholePackageUrls(major, abi).forEach { add(applyMirror(it)) }
        }.distinct()
        return JavaRuntimePackage(
            componentId = "java-$major",
            majorVersion = major,
            displayName = "Java $major",
            abi = abi,
            downloadUrl = urls.first(),
            downloadUrls = urls,
            fileName = fileName,
            packageKind = JavaPackageKind.POJAV_SPLIT_ZIP,
            fallbackUrl = urls.getOrNull(1)
        )
    }

    private fun fallbackWholePackageUrls(major: Int, abi: JavaAbi): List<String> {
        if (abi != JavaAbi.ARM64) return emptyList()
        return when (major) {
            21 -> listOf("$AAAAPAI_BASE/jre21-arm64-20260223-release.tar.xz")
            25 -> listOf("$FCL_JAVA_BASE/jre25-arm64-20251205-release.tar.xz")
            else -> emptyList()
        }
    }

    private fun applyMirror(url: String): String {
        val prefix = mirrorPrefix.trim()
        if (prefix.isEmpty()) return url
        return if (prefix.endsWith("/")) prefix + url else "$prefix/$url"
    }
}
