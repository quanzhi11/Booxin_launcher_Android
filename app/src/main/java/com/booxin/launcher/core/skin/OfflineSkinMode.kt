package com.booxin.launcher.core.skin

/**
 * Offline skin strategy — mirrors PC [OfflineSkinService] modes.
 */
enum class OfflineSkinMode {
    RANDOM,
    STEVE,
    ALEX,
    PLAYER,
    CUSTOM;

    companion object {
        fun parse(value: String?): OfflineSkinMode = when (value?.trim()?.lowercase()) {
            "steve" -> STEVE
            "alex" -> ALEX
            "player" -> PLAYER
            "custom" -> CUSTOM
            else -> RANDOM
        }
    }

    fun toConfigValue(): String = when (this) {
        STEVE -> "steve"
        ALEX -> "alex"
        PLAYER -> "player"
        CUSTOM -> "custom"
        RANDOM -> "random"
    }
}
