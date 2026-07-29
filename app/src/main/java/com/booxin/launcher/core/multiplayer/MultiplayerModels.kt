package com.booxin.launcher.core.multiplayer

data class BooxinUser(
    val id: String,
    val username: String,
    val avatarUrl: String? = null,
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
    val isOnline: Boolean = false,
    val isFriend: Boolean = false
)

data class SearchUser(
    val id: String,
    val username: String,
    val avatarUrl: String? = null,
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
    val status: String? = null
)

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
