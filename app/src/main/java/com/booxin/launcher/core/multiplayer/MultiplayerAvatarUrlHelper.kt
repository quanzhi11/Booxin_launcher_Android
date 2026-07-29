package com.booxin.launcher.core.multiplayer

/**
 * Resolves relative avatar paths the same way as PC [MultiplayerAvatarUrlHelper].
 * API often returns paths like `avatars/xxx.webp` instead of absolute URLs.
 */
object MultiplayerAvatarUrlHelper {

    fun resolveDisplayUrl(avatarUrl: String?, preferredApiRoot: String? = null): String? {
        val raw = avatarUrl?.trim().orEmpty()
        if (raw.isEmpty()) return null

        if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("file://", ignoreCase = true)
        ) {
            return raw
        }

        var path = raw.trimStart('/')
        if (!path.startsWith("api/auth/", ignoreCase = true)) {
            path = "api/auth/$path"
        }
        val root = (preferredApiRoot?.takeIf { it.isNotBlank() } ?: BooxinMultiplayerApi.DEFAULT_ROOT)
            .trimEnd('/')
        return "$root/$path"
    }
}
