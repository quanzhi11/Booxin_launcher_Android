package com.booxin.launcher.core.multiplayer

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object RewardProfileHelper {
    private val utcDay = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

    fun todayUtc(): String = utcDay.format(Instant.now())

    fun hasCheckedInToday(profile: RewardProfile?): Boolean =
        !profile?.lastCheckInDate.isNullOrBlank() && profile.lastCheckInDate == todayUtc()

    fun rankLabel(profile: RewardProfile): String {
        val level = profile.displayLevel?.takeIf { it.isNotBlank() } ?: "LV${profile.level}"
        val title = profile.title?.trim().orEmpty()
        return if (title.isNotEmpty()) "$level · $title" else level
    }
}
