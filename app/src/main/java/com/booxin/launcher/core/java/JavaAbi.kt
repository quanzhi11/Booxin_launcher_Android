package com.booxin.launcher.core.java

import android.os.Build

/**
 * Device ABI used to pick the matching Android OpenJDK package.
 */
enum class JavaAbi(val folderName: String, val packageToken: String) {
    ARM64("arm64-v8a", "arm64"),
    ARM32("armeabi-v7a", "arm"),
    X86_64("x86_64", "x86_64"),
    X86("x86", "x86");

    companion object {
        fun current(): JavaAbi {
            val preferred = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            return when {
                preferred.contains("arm64") -> ARM64
                preferred.contains("armeabi") || preferred == "arm" -> ARM32
                preferred.contains("x86_64") -> X86_64
                preferred.contains("x86") -> X86
                else -> ARM64
            }
        }
    }
}
