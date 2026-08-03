package com.booxin.launcher.core.multiplayer

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Resolves Magisk-built EasyTier Android binaries packaged as native libs
 * (`jniLibs/.../libeasytier_*.so`). Android 10+ blocks exec from filesDir (W^X);
 * only nativeLibraryDir remains executable for ProcessBuilder.
 */
object EasyTierRuntime {
    private const val TAG = "EasyTierRuntime"
    private const val CORE_SO = "libeasytier_core.so"
    private const val CLI_SO = "libeasytier_cli.so"

    data class Paths(val core: File, val cli: File, val dir: File)

    fun ensure(context: Context): Paths {
        val abi = preferredAbi()
        if (abi != "arm64-v8a") {
            error("EasyTier 当前仅内置 arm64-v8a 核心（本机 ABI=$abi）。请使用 arm64 设备联机。")
        }
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val core = File(dir, CORE_SO)
        val cli = File(dir, CLI_SO)
        if (!core.isFile || !cli.isFile) {
            error(
                "EasyTier 运行时缺失（期望 $CORE_SO / $CLI_SO 位于 ${dir.absolutePath}）。" +
                    "请确认 APK 已用 extractNativeLibs=true 打包。"
            )
        }
        if (!isValidElf(core) || !isValidElf(cli)) {
            error("EasyTier 二进制损坏或架构不匹配（需要 arm64 ELF）")
        }
        Log.i(
            TAG,
            "ready nativeLibDir=${dir.absolutePath} core=${core.length()} cli=${cli.length()}"
        )
        return Paths(core = core, cli = cli, dir = dir)
    }

    private fun preferredAbi(): String {
        val abis = Build.SUPPORTED_ABIS
        return when {
            abis.any { it == "arm64-v8a" } -> "arm64-v8a"
            abis.any { it == "armeabi-v7a" } -> "armeabi-v7a"
            abis.any { it == "x86_64" } -> "x86_64"
            else -> abis.firstOrNull() ?: "arm64-v8a"
        }
    }

    private fun isValidElf(file: File): Boolean {
        if (!file.exists() || file.length() < 20) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(20)
                if (input.read(header) < 20) return false
                header[0] == 0x7F.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte() &&
                    header[4] == 2.toByte() && // ELF64
                    (header[18].toInt() and 0xFF) == 0xB7 // AArch64
            }
        }.getOrDefault(false)
    }
}
