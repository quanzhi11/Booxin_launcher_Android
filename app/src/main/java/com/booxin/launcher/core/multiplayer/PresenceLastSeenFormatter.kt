package com.booxin.launcher.core.multiplayer

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Aligns with PC PresenceLastSeenFormatter. */
object PresenceLastSeenFormatter {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val sameYearFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val fullFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val yesterdayTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun formatRelativeLastSeen(presenceUpdatedAtUtc: String?): String {
        val instant = parseUtc(presenceUpdatedAtUtc) ?: return "暂无记录"
        val local = instant.atZone(zone)
        var elapsed = Duration.between(instant, Instant.now())
        if (elapsed.isNegative) elapsed = Duration.ZERO

        return when {
            elapsed.toMinutes() < 1 -> "刚刚"
            elapsed.toHours() < 1 -> "${elapsed.toMinutes().coerceAtLeast(1)} 分钟前"
            elapsed.toDays() < 1 -> "${elapsed.toHours().coerceAtLeast(1)} 小时前"
            local.toLocalDate() == LocalDate.now(zone).minusDays(1) ->
                "昨天 ${local.format(yesterdayTimeFmt)}"
            local.year == LocalDate.now(zone).year -> local.format(sameYearFmt)
            else -> local.format(fullFmt)
        }
    }

    fun formatOfflineStatusText(presenceUpdatedAtUtc: String?): String {
        val relative = formatRelativeLastSeen(presenceUpdatedAtUtc)
        return if (relative == "暂无记录") "离线" else "最近上线 $relative"
    }

    private fun parseUtc(raw: String?): Instant? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching {
                ZonedDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME).toInstant()
            }.getOrNull()
    }
}
