@file:OptIn(ExperimentalTime::class)

package dev.sushnt.journal.ui

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * In the phone's/computer's own time zone:
 * "Today, 14:05" / "Yesterday, 09:30" / "3 Nov, 21:10" / "3 Nov 2025, 21:10"
 */
fun formatEntryDate(epochMillis: Long, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String {
    val zone = TimeZone.currentSystemDefault()
    val at = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date

    val month = at.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    val day = when {
        at.date == today -> "Today"
        at.date == today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
        at.year == today.year -> "${at.date.day} $month"
        else -> "${at.date.day} $month ${at.year}"
    }
    val time = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
    return "$day, $time"
}
