@file:OptIn(ExperimentalTime::class)

package dev.sushnt.journal.ui

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Every date the user sees is in their own time zone.

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

fun localDateTime(epochMillis: Long): LocalDateTime =
    Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())

fun localDate(epochMillis: Long): LocalDate = localDateTime(epochMillis).date

private fun String.titleCase() = lowercase().replaceFirstChar { it.uppercase() }

/** "9:41 PM" */
fun timeLabel(epochMillis: Long): String {
    val t = localDateTime(epochMillis)
    val hour12 = if (t.hour % 12 == 0) 12 else t.hour % 12
    val ampm = if (t.hour < 12) "AM" else "PM"
    return "$hour12:${t.minute.toString().padStart(2, '0')} $ampm"
}

/** "Sunday, 8 November" (adds the year when it isn't this year). */
fun longDateLabel(epochMillis: Long, nowMillis: Long = nowMillis()): String {
    val d = localDate(epochMillis)
    val base = "${d.dayOfWeek.name.titleCase()}, ${d.day} ${d.month.name.titleCase()}"
    return if (d.year == localDate(nowMillis).year) base else "$base ${d.year}"
}

/** List headers: "Today", "Yesterday", "3 Nov", "3 Nov 2025". */
fun dayHeader(day: LocalDate, nowMillis: Long = nowMillis()): String {
    val today = localDate(nowMillis)
    val short = day.month.name.titleCase().take(3)
    return when {
        day == today -> "Today"
        day == today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
        day.year == today.year -> "${day.day} $short"
        else -> "${day.day} $short ${day.year}"
    }
}

/** "just now", "5 min ago", "2 h ago", or a date. */
fun relativeLabel(epochMillis: Long, nowMillis: Long = nowMillis()): String {
    val minutes = (nowMillis - epochMillis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> dayHeader(localDate(epochMillis), nowMillis)
    }
}
