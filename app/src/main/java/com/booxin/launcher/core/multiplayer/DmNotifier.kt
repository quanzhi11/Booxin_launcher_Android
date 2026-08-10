package com.booxin.launcher.core.multiplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.booxin.launcher.R
import com.booxin.launcher.ui.multiplayer.ChatActivity

object DmNotifier {
    const val CHANNEL_ID = "booxin_dm"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.multiplayer_dm_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.multiplayer_dm_channel_desc)
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    fun show(context: Context, conversation: ChatConversation) {
        ensureChannel(context)
        val app = context.applicationContext
        val peerId = conversation.peerUserId
        val peerName = conversation.peerUsername.ifBlank { peerId }
        val body = conversation.lastMessageBody?.trim().orEmpty().ifBlank {
            app.getString(R.string.multiplayer_dm_notify_fallback)
        }
        val title = if (conversation.unreadCount > 1) {
            app.getString(R.string.multiplayer_dm_notify_title_multi, peerName, conversation.unreadCount)
        } else {
            app.getString(R.string.multiplayer_dm_notify_title, peerName)
        }

        val open = Intent(app, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_PEER_ID, peerId)
            putExtra(ChatActivity.EXTRA_PEER_NAME, peerName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            app,
            notificationId(peerId),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(conversation.unreadCount.coerceAtLeast(1))
            .build()

        runCatching {
            NotificationManagerCompat.from(app).notify(notificationId(peerId), notification)
        }
    }

    fun cancel(context: Context, peerUserId: String) {
        NotificationManagerCompat.from(context.applicationContext)
            .cancel(notificationId(peerUserId))
    }

    private fun notificationId(peerUserId: String): Int =
        0x4D000000 or (peerUserId.hashCode() and 0x00FFFFFF)
}
