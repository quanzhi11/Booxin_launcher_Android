package com.booxin.launcher.core.multiplayer

/**
 * Tracks the isolated room-session version created for the current lobby stay,
 * so guests can launch / delete it (PC TrackRoomSessionVersion).
 */
object RoomSessionTracker {
    @Volatile
    var versionId: String? = null
        private set

    fun track(versionId: String?) {
        this.versionId = versionId?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun clear() {
        versionId = null
    }

    fun take(): String? {
        val id = versionId
        versionId = null
        return id
    }
}
