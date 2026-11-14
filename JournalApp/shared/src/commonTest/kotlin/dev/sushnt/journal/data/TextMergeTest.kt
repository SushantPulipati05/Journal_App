package dev.sushnt.journal.data

import kotlin.test.Test
import kotlin.test.assertEquals

class TextMergeTest {
    private val base = "Title\npara one\npara two\npara three"

    @Test
    fun editsInDifferentPlacesAreBothKept() {
        val phone = "Title\npara one EDITED\npara two\npara three"
        val desktop = "Title\npara one\npara two\npara three\nnew para"
        assertEquals("Title\npara one EDITED\npara two\npara three\nnew para", mergeText(base, phone, desktop))
    }

    @Test
    fun sameLineChangedOnBothSidesKeepsBothVersions() {
        val phone = "Title\nA\npara two\npara three"
        val desktop = "Title\nB\npara two\npara three"
        assertEquals("Title\nA\nB\npara two\npara three", mergeText(base, phone, desktop))
    }

    @Test
    fun onlyOneSideChanged() {
        val edited = "Title\npara one\npara 2\npara three"
        assertEquals(edited, mergeText(base, edited, base))
        assertEquals(edited, mergeText(base, base, edited))
    }

    @Test
    fun bothAddedLinesAtTheEnd() {
        assertEquals("Day\nphone line\ndesktop line", mergeText("Day", "Day\nphone line", "Day\ndesktop line"))
    }
}
