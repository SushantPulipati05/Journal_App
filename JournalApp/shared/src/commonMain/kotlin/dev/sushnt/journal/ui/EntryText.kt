package dev.sushnt.journal.ui

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** Max title length the server accepts. */
const val MAX_TITLE = 200

/**
 * The Write page is one block of text. Its first line is the title, the rest is
 * the body, so the stored entry keeps a separate title without a title field.
 */
fun splitEntryText(text: String): Pair<String, String> {
    val firstLine = text.substringBefore('\n')
    val rest = if ('\n' in text) text.substringAfter('\n') else ""
    if (firstLine.length <= MAX_TITLE) return firstLine.trim() to rest
    // A very long first line: keep every character, the overflow starts the body.
    return firstLine.take(MAX_TITLE).trim() to (firstLine.drop(MAX_TITLE) + if (rest.isEmpty()) "" else "\n$rest")
}

/** The opposite of [splitEntryText]: how a saved entry appears on the Write page. */
fun joinEntryText(title: String, body: String): String = when {
    body.isEmpty() -> title
    else -> "$title\n$body"
}

fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

/** About 200 words a minute, never less than 1. */
fun readMinutes(words: Int): Int = maxOf(1, (words + 199) / 200)

/**
 * Days in a row with at least one entry, ending today (or yesterday, so the
 * streak doesn't look broken in the morning before you've written).
 */
fun streakDays(days: Set<LocalDate>, today: LocalDate): Int {
    var day = if (today in days) today else today.minus(1, DateTimeUnit.DAY)
    var count = 0
    while (day in days) {
        count++
        day = day.minus(1, DateTimeUnit.DAY)
    }
    return count
}
