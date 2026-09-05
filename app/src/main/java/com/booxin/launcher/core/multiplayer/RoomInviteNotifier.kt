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
import com.booxin.launcher.ui.MainActivity

object RoomInviteNotifier {
    const val CHANNEL_ID = "booxin_room_invite"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.multiplayer_room_invite_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.multiplayer_room_invite_channel_desc)
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    fun show(context: Context, invite: RoomInvite) {
        ensureChannel(context)
        val app = context.applicationContext
        val sender = invite.fromUsername.ifBlank { app.getString(R.string.multiplayer_room_invite_unknown) }
        val title = app.getString(R.string.multiplayer_room_invite_notify_title, sender)
        val body = app.getString(R.string.multiplayer_room_invite_notify_body, invite.roomCode)

        val open = Intent(app, MainActivity::class.java).apply {
            action = RoomInviteCoordinator.ACTION_ACCEPT
            putExtra(RoomInviteCoordinator.EXTRA_INVITE_ID, invite.inviteId)
            putExtra(RoomInviteCoordinator.EXTRA_ROOM_CODE, invite.roomCode)
            putExtra(RoomInviteCoordinator.EXTRA_SENDER, sender)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPi = PendingIntent.getActivity(
            app,
            notificationId(invite.inviteId),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptPi = PendingIntent.getActivity(
            app,
            notificationId(invite.inviteId) + 1,
            Intent(open).apply { action = RoomInviteCoordinator.ACTION_ACCEPT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissPi = PendingIntent.getBroadcast(
            app,
            notificationId(invite.inviteId) + 2,
            Intent(app, RoomInviteActionReceiver::class.java).apply {
                action = RoomInviteCoordinator.ACTION_DISMISS
                putExtra(RoomInviteCoordinator.EXTRA_INVITE_ID, invite.inviteId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                0,
                app.getString(R.string.multiplayer_room_invite_join),
                acceptPi
            )
            .addAction(
                0,
                app.getString(R.string.multiplayer_dismiss_invite),
                dismissPi
            )
            .build()

        runCatching {
            NotificationManagerCompat.from(app).notify(notificationId(invite.inviteId), notification)
        }
    }

    fun cancel(context: Context, inviteId: String) {
        NotificationManagerCompat.from(context.applicationContext)
            .cancel(notificationId(inviteId))
    }

    private fun notificationId(inviteId: String): Int =
        0x52000000 or (inviteId.hashCode() and 0x00FFFFFF)
}
