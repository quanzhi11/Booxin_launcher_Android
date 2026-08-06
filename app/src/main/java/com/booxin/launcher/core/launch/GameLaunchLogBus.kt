package com.booxin.launcher.core.launch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.booxin.launcher.BooxinApp
import com.booxin.launcher.core.LauncherPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** :game 启动日志广播；同时写入 latest-launch.log。 */
object GameLaunchLogBus {
    const val ACTION_LOG = "com.booxin.launcher.LOG_LINE"
    const val ACTION_FINISHED = "com.booxin.launcher.LOG_FINISHED"
    const val ACTION_FAILED = "com.booxin.launcher.LOG_FAILED"
    const val ACTION_MUTE_UI = "com.booxin.launcher.LOG_MUTE_UI"
    const val EXTRA_LINE = "line"
    const val EXTRA_REASON = "reason"

    val muteUi: AtomicBoolean = AtomicBoolean(false)

    private val writeLock = Any()

    fun latestLogFile(): File? {
        if (!LauncherPaths.isInitialized) return null
        return File(LauncherPaths.rootDir, "logs/latest-launch.log")
    }

    fun beginSession(versionId: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val header = "=== Booxin launch $stamp version=$versionId ===\n"
        val file = latestLogFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(header)
        }
        mirrorExternalWrite(header, append = false)
    }

    fun emit(context: Context, line: String) {
        appendToFile(line)
        if (muteUi.get()) return
        context.sendBroadcast(
            Intent(ACTION_LOG).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_LINE, line)
            }
        )
    }

    fun finished(context: Context) {
        appendToFile("=== launch finished ===")
        context.sendBroadcast(
            Intent(ACTION_FINISHED).apply {
                setPackage(context.packageName)
            }
        )
    }

    fun emitFailed(context: Context, reason: String) {
        appendToFile("=== launch failed: $reason ===")
        context.sendBroadcast(
            Intent(ACTION_FAILED).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_REASON, reason)
                putExtra(EXTRA_LINE, "启动失败: $reason")
            }
        )
    }

    fun muteUiLogs(context: Context) {
        muteUi.set(true)
        context.sendBroadcast(
            Intent(ACTION_MUTE_UI).apply {
                setPackage(context.packageName)
            }
        )
    }

    fun readPersistedLog(maxChars: Int = 120_000): String {
        val file = latestLogFile() ?: return "(launch log path unavailable)"
        if (!file.isFile) return "(no launch log yet)"
        return runCatching {
            val text = file.readText()
            if (text.length <= maxChars) text else text.takeLast(maxChars)
        }.getOrElse { "read launch log failed: ${it.message}" }
    }

    private fun appendToFile(line: String) {
        val file = latestLogFile() ?: return
        synchronized(writeLock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
            }
            mirrorExternalWrite(line + "\n", append = true)
        }
    }

    private fun mirrorExternalWrite(text: String, append: Boolean) {
        runCatching {
            val ctx = BooxinApp.getAppContext()
            val ext = File(ctx.getExternalFilesDir(null), "crash").also { it.mkdirs() }
            val mirror = File(ext, "latest-launch.log")
            if (append) mirror.appendText(text) else mirror.writeText(text)
        }
    }

    fun register(
        context: Context,
        onLine: (String) -> Unit,
        onFinished: () -> Unit,
        onFailed: ((String) -> Unit)? = null
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_LOG -> onLine(intent.getStringExtra(EXTRA_LINE).orEmpty())
                    ACTION_FINISHED -> onFinished()
                    ACTION_FAILED -> {
                        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
                        intent.getStringExtra(EXTRA_LINE)?.takeIf { it.isNotBlank() }?.let(onLine)
                        onFailed?.invoke(reason.ifBlank { "unknown" })
                    }
                    ACTION_MUTE_UI -> muteUi.set(true)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(ACTION_LOG)
                addAction(ACTION_FINISHED)
                addAction(ACTION_FAILED)
                addAction(ACTION_MUTE_UI)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        return receiver
    }
}
