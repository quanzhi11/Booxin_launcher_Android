package com.booxin.launcher.core.multiplayer

import android.content.Context
import com.booxin.launcher.core.diag.DiagEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Unpublish public rooms left behind when `:game` crashed / was killed
 * without calling DELETE /api/rooms/{code}.
 */
object HostedRoomCleanup {
    private const val TAG = "HostedRoomCleanup"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun sweepAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { sweep(app) }
    }

    suspend fun sweep(context: Context) = mutex.withLock {
        val lease = HostedRoomLease.load(context) ?: return
        if (HostedRoomLease.isHostProcessAlive(lease)) {
            DiagEventLog.i(TAG, "skip sweep room=${lease.roomCode} hostPid=${lease.hostPid} still alive")
            return
        }
        DiagEventLog.i(TAG, "unpublish zombie room=${lease.roomCode} deadPid=${lease.hostPid}")
        BooxinRoomApi().deleteRoom(lease.roomCode).onFailure { err ->
            DiagEventLog.w(TAG, "zombie unpublish failed: ${err.message}")
        }
        // Clear even on API failure so we do not spam forever; server also drops
        // rooms with LastPingAt older than 30 minutes from the public list.
        HostedRoomLease.clear(context, lease.roomCode)
    }
}
