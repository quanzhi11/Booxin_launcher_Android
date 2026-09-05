package com.booxin.launcher.core.multiplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.booxin.launcher.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles notification dismiss for room invites. */
class RoomInviteActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != RoomInviteCoordinator.ACTION_DISMISS) return
        val inviteId = intent.getStringExtra(RoomInviteCoordinator.EXTRA_INVITE_ID).orEmpty()
        if (inviteId.isBlank()) return
        RoomInviteNotifier.cancel(context, inviteId)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppContainer.multiplayerAuth.dismissInvite(inviteId)
            } finally {
                pending.finish()
            }
        }
    }
}
