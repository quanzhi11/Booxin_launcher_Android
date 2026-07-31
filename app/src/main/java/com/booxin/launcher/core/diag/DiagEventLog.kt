package com.booxin.launcher.core.diag

import android.util.Log
import com.booxin.launcher.core.LauncherPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app ring buffer + file for release builds where logcat dumps are empty.
 */
object DiagEventLog {
    private const val TAG = "BooxinDiag"
    private const val MAX_CHARS = 120_000
    private val lock = Any()
    private val memory = StringBuilder()

    fun i(tag: String, message: String) {
        append("I", tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        append("W", tag, message)
        Log.w(tag, message)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        val detail = if (t == null) message else "$message: ${t.message}"
        append("E", tag, detail)
        if (t != null) Log.e(tag, message, t) else Log.e(tag, message)
    }

    fun readPersisted(maxChars: Int = MAX_CHARS): String {
        val file = logFile()
        val fromFile = runCatching {
            if (file != null && file.isFile) file.readText() else ""
        }.getOrDefault("")
        val combined = synchronized(lock) {
            if (fromFile.isNotBlank()) fromFile else memory.toString()
        }
        if (combined.isBlank()) return "(no app event log yet)"
        return if (combined.length <= maxChars) combined else combined.takeLast(maxChars)
    }

    private fun append(level: String, tag: String, message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$stamp $level/$tag: ${message.take(2000)}\n"
        synchronized(lock) {
            memory.append(line)
            if (memory.length > MAX_CHARS) {
                memory.delete(0, memory.length - MAX_CHARS)
            }
            val file = logFile() ?: return
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line)
                if (file.length() > MAX_CHARS * 2L) {
                    val trimmed = file.readText().takeLast(MAX_CHARS)
                    file.writeText(trimmed)
                }
            }
        }
    }

    private fun logFile(): File? {
        if (!LauncherPaths.isInitialized) return null
        return File(LauncherPaths.rootDir, "logs/app-events.log")
    }
}
