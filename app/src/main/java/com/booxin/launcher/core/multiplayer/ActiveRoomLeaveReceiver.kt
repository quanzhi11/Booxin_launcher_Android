package com.booxin.launcher.core.multiplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.booxin.launcher.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Main-process leave when `:game` (or another process) requests it. */
class ActiveRoomLeaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ActiveRoomSessionStore.ACTION_LEAVE_ACTIVE_ROOM) return
        val pending = goAsync()
        scope.launch {
            try {
                AppContainer.multiplayerAuth.leaveActiveRoom()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
