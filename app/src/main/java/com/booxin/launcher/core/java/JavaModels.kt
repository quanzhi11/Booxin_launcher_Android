package com.booxin.launcher.core.java

/**
 * A downloadable Android-compatible Java runtime package.
 */
data class JavaRuntimePackage(
    val componentId: String,
    val majorVersion: Int,
    val displayName: String,
    val abi: JavaAbi,
    val downloadUrl: String,
    val fileName: String,
    val sha256: String? = null,
    val packageKind: JavaPackageKind = JavaPackageKind.WHOLE_ARCHIVE,
    val fallbackUrl: String? = null
)

enum class JavaPackageKind {
    /** Single jreN-{abi}-*.tar.xz (or zip) that already contains a full JRE tree. */
    WHOLE_ARCHIVE,

    /** FCL/Pojav split zip: universal.tar.xz + bin-{abi}.tar.xz (+ version). */
    POJAV_SPLIT_ZIP
}

enum class JavaInstallState {
    NOT_INSTALLED,
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
    FAILED
}

data class JavaInstallProgress(
    val componentId: String,
    val state: JavaInstallState,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val message: String = ""
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            -1f
        }
}

data class InstalledJavaRuntime(
    val componentId: String,
    val majorVersion: Int,
    val homeDir: java.io.File,
    val javaBinary: java.io.File
)
