package com.booxin.launcher.core.launch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Cross-process log bridge: [GameLaunchService] (:game) broadcasts lines,
 * [LaunchActivity] (main process) receives them.
 */
object GameLaunchLogBus {
    const val ACTION_LOG = "com.booxin.launcher.LOG_LINE"
    const val ACTION_FINISHED = "com.booxin.launcher.LOG_FINISHED"
    const val EXTRA_LINE = "line"

    fun emit(context: Context, line: String) {
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
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(ACTION_LOG)
                addAction(ACTION_FINISHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        return receiver
    }
}
