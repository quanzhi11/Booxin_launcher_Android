package com.booxin.launcher.core.launch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-process log bridge: [GameLaunchService] (:game) broadcasts lines,
 * [LaunchActivity] (main process) receives them.
 *
 * After the loading overlay hides, [muteUi] stops Binder spam — mirroring every
 * Minecraft log line onto the UI thread was the main cause of touch lag / heat.
 */
object GameLaunchLogBus {
    const val ACTION_LOG = "com.booxin.launcher.LOG_LINE"
    const val ACTION_FINISHED = "com.booxin.launcher.LOG_FINISHED"
    const val ACTION_MUTE_UI = "com.booxin.launcher.LOG_MUTE_UI"
    const val EXTRA_LINE = "line"

    /** Process-local (:game). Cleared on each new launch. */
    val muteUi: AtomicBoolean = AtomicBoolean(false)

    fun emit(context: Context, line: String) {
        if (muteUi.get()) return
        context.sendBroadcast(
            Intent(ACTION_LOG).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_LINE, line)
            }
        )
    }

    fun finished(context: Context) {
        context.sendBroadcast(
            Intent(ACTION_FINISHED).apply {
                setPackage(context.packageName)
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

    fun register(
        context: Context,
        onLine: (String) -> Unit,
        onFinished: () -> Unit
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_LOG -> onLine(intent.getStringExtra(EXTRA_LINE).orEmpty())
                    ACTION_FINISHED -> onFinished()
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
                addAction(ACTION_MUTE_UI)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        return receiver
    }
}
