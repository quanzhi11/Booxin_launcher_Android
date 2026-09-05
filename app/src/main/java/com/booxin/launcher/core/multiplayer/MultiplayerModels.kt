package com.booxin.launcher.core.multiplayer

data class BooxinUser(
    val id: String,
    val username: String,
    val avatarUrl: String? = null,
    val selectedFrameId: String? = null,
    val signature: String? = null,
    val email: String? = null,
    val isEmailVerified: Boolean = false,
    val isOnline: Boolean = false,
    val isInRoom: Boolean = false
)

data class BooxinAuthSession(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresAtUtc: String? = null,
    val user: BooxinUser,
    val apiRoot: String
)

data class BooxinFriend(
    val userId: String,
    val username: String,
    val avatarUrl: String? = null,
    val selectedFrameId: String? = null,
    val isOnline: Boolean = false,
    val isInRoom: Boolean = false,
    val isAvailableToChat: Boolean = false,
    val relationship: String? = null,
    val roomCode: String? = null
)

data class FriendRequest(
    val requestId: String,
    val userId: String,
    val username: String,
    val avatarUrl: String? = null
)

data class RoomInvite(
    val inviteId: String,
    val fromUserId: String,
    val fromUsername: String,
    val fromAvatarUrl: String? = null,
    val roomCode: String,
    val createdAtUtc: String? = null
)

data class BlockedUser(
    val userId: String,
    val username: String,
    val avatarUrl: String? = null
)

data class FriendsDashboard(
    val friends: List<BooxinFriend>,
    val incomingRequests: List<FriendRequest>,
    val outgoingRequests: List<FriendRequest> = emptyList(),
    val pendingRoomInvites: List<RoomInvite> = emptyList(),
    val blockedUsers: List<BlockedUser> = emptyList()
)

data class LobbyUser(
    val id: String,
    val username: String,
    val avatarUrl: String? = null,
    val selectedFrameId: String? = null,
    val isOnline: Boolean = false,
    val isFriend: Boolean = false,
    val hasPendingOutgoingRequest: Boolean = false,
    val hasPendingIncomingRequest: Boolean = false,
    val pendingIncomingRequestId: String? = null
)

data class LobbyPage(
    val users: List<LobbyUser> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 30,
    val totalCount: Int = 0,
    val totalPages: Int = 0
) {
    val hasPrevious: Boolean get() = page > 1
    val hasNext: Boolean get() = totalPages > 0 && page < totalPages
}

data class SearchUser(
    val id: String,
    val username: String,
    val avatarUrl: String? = null,
    val selectedFrameId: String? = null,
    val isOnline: Boolean = false,
    val isFriend: Boolean = false
)

data class ChatMessage(
    val id: Long,
    val senderId: String,
    val receiverId: String,
    val body: String,
    val sentAtUtc: String?,
    val isMine: Boolean,
    val isRevoked: Boolean,
    val canRecall: Boolean
)

data class LobbyChatMessage(
    val id: Long,
    val senderId: String,
    val senderUsername: String,
    val senderAvatarUrl: String? = null,
    val body: String,
    val sentAtUtc: String?,
    val isMine: Boolean
)

data class ChatConversation(
    val peerUserId: String,
    val peerUsername: String,
    val peerAvatarUrl: String? = null,
    val lastMessageBody: String? = null,
    val lastMessageAtUtc: String? = null,
    val unreadCount: Int = 0
)

data class PublicRoom(
    val id: String,
    val roomCode: String,
    val hostName: String,
    val motd: String,
    val remark: String? = null,
    val port: Int = 25565,
    val maxPlayers: Int = 0,
    val currentPlayers: Int = 0,
    val isPublic: Boolean = true,
    val version: String? = null,
    val modpackUrl: String? = null,
    val modpackGameVersion: String? = null,
    val modpackLoader: String? = null,
    val modsJson: String? = null,
    val mods: List<RoomModDependency> = emptyList(),
    val status: String? = null
) {
    fun resolveMods(): List<RoomModDependency> =
        when {
            mods.isNotEmpty() -> mods
            else -> RoomHostDependencyService.deserializeMods(modsJson)
        }
}

data class TerracottaLobbyInfo(
    val roomCode: String,
    val networkName: String,
    val networkSecret: String,
    val minecraftPort: Int,
    val virtualHostIp: String = "10.144.144.1"
)

/** Scaffolding player profile (HOST / GUEST). */
data class RoomMember(
    val name: String,
    val machineId: String,
    val vendor: String = "",
    val kind: String? = null
) {
    val isHost: Boolean get() = kind.equals("HOST", ignoreCase = true)
}

data class RewardProfile(
    val booxinUserId: String,
    val username: String,
    val gold: Int = 0,
    val level: Int = 0,
    val title: String? = null,
    val displayLevel: String? = null,
    val ownedFrameIds: List<String> = emptyList(),
    val selectedFrameId: String? = null,
    val lastCheckInDate: String? = null
)

data class RewardClaimResult(
    val ok: Boolean = false,
    val message: String? = null,
    val alreadyClaimed: Boolean = false,
    val awarded: Int = 0,
    val profile: RewardProfile? = null
)

/** Aligns with PC MultiplayerUserDetail / UserDetailDialog. */
data class BooxinUserDetail(
    val id: String,
    val username: String,
    val avatarUrl: String? = null,
    val signature: String? = null,
    val createdAtUtc: String? = null,
    val presenceStatus: String = "offline",
    val presenceUpdatedAtUtc: String? = null,
    val isFriend: Boolean = false,
    val isBlocked: Boolean = false,
    val isBlockedBy: Boolean = false,
    val relationship: String? = null
) {
    val presenceStatusText: String
        get() = when (presenceStatus.lowercase()) {
            "availabletochat" -> "可聊天"
            "inroom" -> "在房间"
            "online" -> "在线"
            else -> "离线"
        }

    val statusDisplayText: String
        get() = if (!presenceStatus.equals("offline", ignoreCase = true)) {
            presenceStatusText
        } else {
            PresenceLastSeenFormatter.formatOfflineStatusText(presenceUpdatedAtUtc)
        }
}

object MultiplayerSocialConstants {
    const val MAX_SIGNATURE_LENGTH = 80
    val RELATIONSHIP_PRESETS = listOf("好友", "闺蜜", "兄弟", "同学", "同事", "网友")
}
