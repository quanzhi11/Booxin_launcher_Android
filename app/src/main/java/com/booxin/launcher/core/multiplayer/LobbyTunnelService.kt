package com.booxin.launcher.core.multiplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.booxin.launcher.R
import com.booxin.launcher.core.diag.DiagEventLog
import com.booxin.launcher.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the main-process lobby tunnel (EasyTier child + LocalTcpRelay) at foreground
 * priority so Android is less likely to kill the EasyTier phantom process while the
 * game runs in `:game`.
 */
class LobbyTunnelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopWatch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val addr = intent?.getStringExtra(EXTRA_ADDRESS).orEmpty()
                ensureChannel()
                val notification = buildNotification(addr.ifBlank { "联机中" })
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } catch (_: Throwable) {
                    startForeground(NOTIFICATION_ID, notification)
                }
                startWatch(addr)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopWatch()
        scope.cancel()
        super.onDestroy()
    }

    private fun startWatch(addr: String) {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                val session = EasyTierSessionHolder.current
                if (session == null) {
                    DiagEventLog.w(TAG, "watch: EasyTierSession gone")
                    updateNotification("$addr（隧道已停止）")
                    delay(3_000)
                    continue
                }
                val alive = runCatching {
                    // Touch process via a cheap CLI; failures are logged.
                    session.readPeers()
                    true
                }.getOrElse { err ->
                    DiagEventLog.w(TAG, "watch: EasyTier unhealthy: ${err.message}")
                    false
                }
                updateNotification(
                    if (alive) addr.ifBlank { "联机隧道运行中" }
                    else "${addr.ifBlank { "联机隧道" }}（异常，请重新加入房间）"
                )
                delay(4_000)
            }
        }
    }

    private fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.multiplayer_tunnel_fg_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.multiplayer_tunnel_fg_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(content: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.multiplayer_tunnel_fg_title))
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    companion object {
        private const val TAG = "LobbyTunnelService"
        private const val CHANNEL_ID = "booxin_lobby_tunnel"
        private const val NOTIFICATION_ID = 0xB00A
        const val ACTION_STOP = "com.booxin.launcher.action.STOP_LOBBY_TUNNEL"
        const val EXTRA_ADDRESS = "address"

        fun start(context: Context, address: String) {
            val i = Intent(context, LobbyTunnelService::class.java)
                .putExtra(EXTRA_ADDRESS, address)
            try {
                context.startForegroundService(i)
            } catch (t: Throwable) {
                DiagEventLog.w(TAG, "startForegroundService failed: ${t.message}")
                runCatching { context.startService(i) }
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, LobbyTunnelService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
            runCatching { context.stopService(Intent(context, LobbyTunnelService::class.java)) }
        }
    }
}
