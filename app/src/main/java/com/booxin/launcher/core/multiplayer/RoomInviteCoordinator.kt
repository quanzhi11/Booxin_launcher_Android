package com.booxin.launcher.core.multiplayer

import android.content.Context
import android.content.Intent
import com.booxin.launcher.ui.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges PC-style one-click room invites onto Android:
 * foreground → in-app dialog; background → notification; accept → auto-join.
 */
object RoomInviteCoordinator {
    const val ACTION_ACCEPT = "com.booxin.launcher.action.ACCEPT_ROOM_INVITE"
    const val ACTION_DISMISS = "com.booxin.launcher.action.DISMISS_ROOM_INVITE"
    const val EXTRA_INVITE_ID = "room_invite_id"
    const val EXTRA_ROOM_CODE = "room_code"
    const val EXTRA_SENDER = "room_invite_sender"

    data class PendingJoin(
        val roomCode: String,
        val inviteId: String? = null
    )

    private val shownInviteIds = ConcurrentHashMap.newKeySet<String>()

    private val _dialogInvites = MutableSharedFlow<RoomInvite>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val dialogInvites: SharedFlow<RoomInvite> = _dialogInvites.asSharedFlow()

    @Volatile
    var pendingJoin: PendingJoin? = null
        private set

    fun clearShown() {
        shownInviteIds.clear()
    }

    fun consumePendingJoin(): PendingJoin? {
        val next = pendingJoin
        pendingJoin = null
        return next
    }

    fun requestJoin(roomCode: String, inviteId: String? = null) {
        val code = roomCode.trim()
        if (code.isEmpty()) return
        pendingJoin = PendingJoin(code, inviteId?.takeIf { it.isNotBlank() })
    }

    fun onInvitesPolled(context: Context, invites: List<RoomInvite>) {
        val currentIds = invites.map { it.inviteId }.filter { it.isNotBlank() }.toSet()
        shownInviteIds.retainAll(currentIds)
        for (invite in invites) {
            if (invite.inviteId.isBlank() || invite.roomCode.isBlank()) continue
            if (!shownInviteIds.add(invite.inviteId)) continue
            if (AppForeground.isForeground) {
                _dialogInvites.tryEmit(invite)
            } else {
                RoomInviteNotifier.show(context, invite)
            }
        }
    }

    fun openAcceptUi(context: Context, invite: RoomInvite) {
        val app = context.applicationContext
        requestJoin(invite.roomCode, invite.inviteId)
        RoomInviteNotifier.cancel(app, invite.inviteId)
        val open = Intent(app, MainActivity::class.java).apply {
            action = ACTION_ACCEPT
            putExtra(EXTRA_INVITE_ID, invite.inviteId)
            putExtra(EXTRA_ROOM_CODE, invite.roomCode)
            putExtra(EXTRA_SENDER, invite.fromUsername)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        app.startActivity(open)
    }

    fun markShown(inviteId: String) {
        if (inviteId.isNotBlank()) shownInviteIds.add(inviteId)
    }
}
