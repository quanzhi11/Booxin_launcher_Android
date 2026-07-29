package com.booxin.launcher.core.update

/**
 * Payload of https://www.boonix.art/server_update/phones.json
 */
data class LauncherUpdateInfo(
    val latestVersion: String,
    val latestVersionCode: Int,
    val minSupportedVersionCode: Int = 1,
    val forceUpdate: Boolean = false,
    val releaseNotes: String = "",
    val apkUrl: String = "",
    val apkSha256: String = "",
    val publishedAt: String = ""
) {
    fun isNewerThan(localVersionCode: Int, localVersionName: String): Boolean {
        if (latestVersionCode > localVersionCode) return true
        // Allow servers that only bump latestVersion (e.g. 999.99.9) without versionCode.
        if (compareVersionNames(latestVersion, localVersionName) > 0) return true
        return false
    }

    fun isBelowMinimum(localVersionCode: Int): Boolean =
        localVersionCode < minSupportedVersionCode

    fun hasApk(): Boolean = apkUrl.isNotBlank()

    companion object {
        /** Compare dotted numeric versions: 1.2.10 > 1.2.9, 999.99.9 > 0.0.1 */
        fun compareVersionNames(a: String, b: String): Int {
            val pa = a.trim().removePrefix("v").removePrefix("V")
                .split('.', '-', '_')
                .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val pb = b.trim().removePrefix("v").removePrefix("V")
                .split('.', '-', '_')
                .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }
}
