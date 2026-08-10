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
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Polls DM conversations while a Booxin multiplayer session is active and posts
 * system notifications for newly increased unread counts (PC Toast equivalent).
 */
object DmInboxWatcher {
    private const val POLL_MS = 8_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var watchJob: Job? = null

    @Volatile
    var activePeerId: String? = null

    /** peerId → last seen unreadCount (and last body fingerprint after seed). */
    private val lastUnread = ConcurrentHashMap<String, Int>()
    private val lastBody = ConcurrentHashMap<String, String>()

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        DmNotifier.ensureChannel(app)
        // Touch auth manager so restored session is loaded.
        val auth = AppContainer.multiplayerAuth
        watchJob = scope.launch {
            auth.session.collectLatest { session ->
                if (session == null) {
                    lastUnread.clear()
                    lastBody.clear()
                    return@collectLatest
                }
                seed(auth)
                while (coroutineContext.isActive) {
                    delay(POLL_MS)
                    poll(app, auth)
                }
            }
        }
    }

    private suspend fun seed(auth: MultiplayerAuthManager) {
        val list = auth.listConversations().getOrNull().orEmpty()
        lastUnread.clear()
        lastBody.clear()
        list.forEach { c ->
            lastUnread[c.peerUserId] = c.unreadCount
            lastBody[c.peerUserId] = c.lastMessageBody.orEmpty()
        }
    }

    private suspend fun poll(context: Context, auth: MultiplayerAuthManager) {
        if (auth.current() == null) return
        val list = auth.listConversations().getOrNull() ?: return
        for (c in list) {
            val prevUnread = lastUnread[c.peerUserId] ?: 0
            val prevBody = lastBody[c.peerUserId].orEmpty()
            val body = c.lastMessageBody.orEmpty()
            lastUnread[c.peerUserId] = c.unreadCount
            lastBody[c.peerUserId] = body

            if (c.unreadCount <= 0) {
                DmNotifier.cancel(context, c.peerUserId)
                continue
            }
            val active = activePeerId
            if (!active.isNullOrBlank() && active == c.peerUserId) {
                DmNotifier.cancel(context, c.peerUserId)
                continue
            }
            val increased = c.unreadCount > prevUnread
            val newPreview = body.isNotBlank() && body != prevBody && c.unreadCount > 0
            if (increased || newPreview) {
                DmNotifier.show(context, c)
            }
        }
    }
}
