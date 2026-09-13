package com.booxin.launcher.core.multiplayer

/**
 * Cloud dedicated server DTO — aligned with PC CloudServerService / BooxinCloudServerApi.
 */
data class CloudServerInfo(
    val instanceId: String,
    val ownerUsername: String = "",
    val name: String? = null,
    val gameVersion: String = "",
    val host: String = "",
    val port: Int = 0,
    val address: String = "",
    val status: String = "",
    val errorMessage: String? = null,
    val createdAtUtc: String? = null,
    val expiresAtUtc: String? = null,
    val lastHeartbeatUtc: String? = null
) {
    val resolvedAddress: String
        get() = address.trim().ifBlank {
            val h = host.trim()
            when {
                h.isBlank() -> ""
                port > 0 -> "$h:$port"
                else -> h
            }
        }

    val isRunning: Boolean
        get() = status.equals("Running", ignoreCase = true)

    val isActive: Boolean
        get() = status.equals("Provisioning", ignoreCase = true) ||
            status.equals("Starting", ignoreCase = true) ||
            status.equals("Running", ignoreCase = true) ||
            status.equals("Stopping", ignoreCase = true)

    val isTerminalFailure: Boolean
        get() = status.equals("Failed", ignoreCase = true) ||
            status.equals("Crashed", ignoreCase = true) ||
            status.equals("Stopped", ignoreCase = true)
}

data class CloudServerCapacity(
    val used: Int = 0,
    val max: Int = 2,
    val allowedVersions: List<String> = emptyList(),
    val canCreate: Boolean = false,
    val isWhitelisted: Boolean = false
)

class CloudServerApiException(
    val code: String,
    override val message: String,
    val statusCode: Int
) : Exception(message)
