package com.booxin.launcher.core.launch

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.booxin.launcher.core.LauncherPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Optional full launch log capture (settings → start / stop & export). */
object RealtimeLaunchLog {
    private const val FLAG_NAME = "realtime-launch.enabled"

    fun isEnabled(): Boolean {
        val flag = flagFile() ?: return false
        return flag.isFile
    }

    fun enable() {
        val flag = flagFile() ?: return
        runCatching {
            flag.parentFile?.mkdirs()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
            flag.writeText("enabledAt=$stamp\n")
        }
    }

    fun disable() {
        flagFile()?.delete()
    }

    fun statusText(): String {
        if (!isEnabled()) return "未开启"
        val started = runCatching { flagFile()?.readText()?.trim().orEmpty() }
            .getOrDefault("")
            .lineSequence()
            .firstOrNull { it.startsWith("enabledAt=") }
            ?.removePrefix("enabledAt=")
            ?.trim()
            .orEmpty()
        val log = GameLaunchLogBus.latestLogFile()
        val sizeKb = if (log != null && log.isFile) (log.length() / 1024L) else 0L
        return if (started.isNotBlank()) {
            "录制中（自 $started）· 当前日志约 ${sizeKb}KB"
        } else {
            "录制中 · 当前日志约 ${sizeKb}KB"
        }
    }

    fun stopAndExport(context: Context): Result<File> = runCatching {
        disable()
        val source = GameLaunchLogBus.latestLogFile()
            ?: error("日志路径不可用")
        if (!source.isFile || source.length() <= 0L) {
            error("还没有启动日志，请先开启后启动一次游戏")
        }
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "booxin-realtime-launch-$stamp.txt")
        val header = buildString {
            appendLine("=== Booxin 启动实时日志 ===")
            appendLine("exported=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine("source=${source.absolutePath}")
            appendLine("bytes=${source.length()}")
            appendLine()
        }
        out.writeText(header + source.readText())
        share(context, out)
        out
    }

    private fun flagFile(): File? {
        if (!LauncherPaths.isInitialized) return null
        return File(LauncherPaths.rootDir, "logs/$FLAG_NAME")
    }

    private fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Booxin 启动实时日志 ${file.name}")
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Booxin 启动实时日志，请发给开发者。")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "导出启动实时日志")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
