package com.booxin.launcher.core.multiplayer

import android.content.Context
import com.booxin.launcher.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Polls pending room invites while logged into Booxin (PC friends invite equivalent).
 */
object RoomInviteWatcher {
    private const val POLL_MS = 6_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var watchJob: Job? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        RoomInviteNotifier.ensureChannel(app)
        val auth = AppContainer.multiplayerAuth
        watchJob = scope.launch {
            auth.session.collectLatest { session ->
                if (session == null) {
                    RoomInviteCoordinator.clearShown()
                    return@collectLatest
                }
                // Immediate check (matches PC popup on friends refresh), then poll.
                poll(app, auth)
                while (coroutineContext.isActive) {
                    delay(POLL_MS)
                    poll(app, auth)
                }
            }
        }
    }

    private suspend fun poll(context: Context, auth: MultiplayerAuthManager) {
        if (auth.current() == null) return
        val dash = auth.loadFriends().getOrNull() ?: return
        RoomInviteCoordinator.onInvitesPolled(context, dash.pendingRoomInvites)
    }
}
