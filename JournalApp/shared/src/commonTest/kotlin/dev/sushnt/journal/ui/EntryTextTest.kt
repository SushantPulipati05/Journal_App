package dev.sushnt.journal.ui

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class EntryTextTest {

    @Test
    fun firstLineIsTheTitle() {
        assertEquals("Rain" to "It was cold.\nThen warm.", splitEntryText("Rain\nIt was cold.\nThen warm."))
        assertEquals("Just a title" to "", splitEntryText("Just a title"))
        assertEquals("" to "", splitEntryText(""))
    }

    @Test
    fun splitAndJoinRoundTrip() {
        val text = "Morning\nCoffee by the window.\n\nThen class."
        val (title, body) = splitEntryText(text)
        assertEquals(text, joinEntryText(title, body))
    }

    @Test
    fun veryLongFirstLineKeepsEveryCharacter() {
        val line = "a".repeat(250)
        val (title, body) = splitEntryText(line)
        assertEquals(MAX_TITLE, title.length)
        assertEquals(50, body.length)
    }

    @Test
    fun countsWords() {
        assertEquals(0, wordCount("   "))
        assertEquals(4, wordCount("one two\nthree   four"))
        assertEquals(1, readMinutes(10))
        assertEquals(2, readMinutes(201))
    }

    @Test
    fun streakCountsConsecutiveDays() {
        val today = LocalDate(2026, 11, 8)
        val days = setOf(LocalDate(2026, 11, 8), LocalDate(2026, 11, 7), LocalDate(2026, 11, 6), LocalDate(2026, 11, 3))
        assertEquals(3, streakDays(days, today))
        // Nothing yet today, but yesterday counts: the streak isn't broken until tomorrow.
        assertEquals(2, streakDays(setOf(LocalDate(2026, 11, 7), LocalDate(2026, 11, 6)), today))
        assertEquals(0, streakDays(setOf(LocalDate(2026, 11, 1)), today))
    }
}
