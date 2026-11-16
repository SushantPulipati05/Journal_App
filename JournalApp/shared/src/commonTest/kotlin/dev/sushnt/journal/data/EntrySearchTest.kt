package dev.sushnt.journal.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntrySearchTest {
    private fun store() = EntryStore.open(BundledSQLiteDriver(), ":memory:")

    private fun ids(hits: List<SearchHit>) = hits.map { it.entry.title }

    @Test
    fun findsWordsAndPrefixes() = runTest {
        val s = store()
        s.create("Interview day", "Had my first interview at Infosys.")
        s.create("Café visit", "Talked about interviews near Koregaon Park.")
        s.create("Gym", "Leg day with Karan.")

        assertEquals(listOf("Interview day", "Café visit"), ids(s.search("interv")))
        assertEquals(listOf("Interview day"), ids(s.search("interview infosys")))
        assertEquals(listOf("Café visit"), ids(s.search("cafe"))) // accents don't matter
        assertEquals(listOf("Café visit"), ids(s.search("koregaon-park"))) // punctuation can't break the query
        assertTrue(s.search("   ").isEmpty())
    }

    @Test
    fun titleMatchesRankFirst() = runTest {
        val s = store()
        s.create("Monday", "Long day. Ended with a quick run.")
        s.create("Run", "Short note.")
        assertEquals("Run", s.search("run").first().entry.title)
    }

    @Test
    fun snippetMarksTheWords() = runTest {
        val s = store()
        s.create("Day", "Went to the market and bought mangoes.")
        val snippet = s.search("mango").single().snippet
        assertTrue("${SnippetStart}mangoes$SnippetEnd" in snippet, snippet)
    }

    @Test
    fun followsEditsAndDeletes() = runTest {
        val s = store()
        val e = s.create("Plans", "Buy milk")
        s.update(e.id, "Plans", "Buy bread")
        assertTrue(s.search("milk").isEmpty())
        assertEquals(1, s.search("bread").size)
        s.delete(e.id)
        assertTrue(s.search("bread").isEmpty())
    }

    @Test
    fun ignoresPhotosAndFormatting() = runTest {
        val s = store()
        s.create("List", "- [ ] call mom\n{{photo:11111111-1111-1111-1111-111111111111}}\n**big** news")
        assertTrue(s.search("photo").isEmpty())
        assertEquals(1, s.search("big news").size)
        assertTrue("**" !in s.search("big").single().snippet)
    }

    @Test
    fun queryBuilding() {
        assertNull(EntryStore.ftsQuery("  -- "))
        assertEquals("\"interview\" \"day\"*", EntryStore.ftsQuery("interview day"))
        assertEquals("\"bad\" \"query\"*", EntryStore.ftsQuery("\"bad: query*"))
    }
}
