package dev.sushnt.journal.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.sushnt.journal.api.InMemoryTokenStore
import dev.sushnt.journal.api.JournalApi
import dev.sushnt.journal.api.PushRequest
import dev.sushnt.journal.api.Tokens
import dev.sushnt.journal.apiJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = "https://server.test"

private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

private fun HttpRequestData.pushed(): PushRequest =
    apiJson.decodeFromString(PushRequest.serializer(), (body as TextContent).text)

private fun emptyPull(cursor: Long = 0) = """{"changes":[],"next_cursor":$cursor,"has_more":false}"""

private fun change(id: String, title: String, updated: String, version: Long, deleted: String? = null) =
    """{"id":"$id","title":"$title","body":"b","created_at":"2026-01-01T00:00:00Z","updated_at":"$updated",""" +
        """"deleted_at":${if (deleted == null) "null" else "\"$deleted\""},"version":$version}"""

class SyncEngineTest {
    private var clock = 1_000_000L

    private fun newStore() = EntryStore.open(
        BundledSQLiteDriver(),
        ":memory:",
        now = { clock++ },
        newId = { "id-${clock++}" },
    )

    private fun newApi(engine: MockEngine) =
        JournalApi(BASE, InMemoryTokenStore(Tokens("A", "R")), engine)

    // ---------- the local store on its own ----------

    @Test
    fun createUpdateDeleteLifecycle() = runTest {
        val store = newStore()
        val e = store.create("Day 1", "Hello")
        assertEquals(listOf("Day 1"), store.list().map { it.title })
        assertTrue(store.get(e.id)!!.dirty)

        assertTrue(store.update(e.id, "Day 1!", "Hello again"))
        assertEquals("Hello again", store.get(e.id)!!.body)

        assertTrue(store.delete(e.id))
        assertTrue(store.list().isEmpty(), "deleted entries are hidden")
        val tomb = store.get(e.id)!!
        assertEquals("", tomb.title, "text is erased on delete")
        assertEquals("", tomb.body)
        assertTrue(tomb.deletedAt != null && tomb.dirty)
        assertFalse(store.update(e.id, "x", "y"), "cannot edit a deleted entry")
    }

    @Test
    fun updatedAtAlwaysGrowsEvenIfTheClockStandsStill() = runTest {
        val store = EntryStore.open(BundledSQLiteDriver(), ":memory:", now = { 5L }, newId = { "x" })
        val e = store.create("a", "b")
        store.update("x", "a2", "b2")
        assertTrue(store.get(e.id)!!.updatedAt > e.updatedAt)
    }

    // ---------- sync ----------

    @Test
    fun pushSendsDirtyEntriesAndMarksThemClean() = runTest {
        val store = newStore()
        val e = store.create("Hi", "there")
        var request: PushRequest? = null
        val engine = MockEngine { r ->
            if (r.url.encodedPath == "/sync/push") {
                request = r.pushed()
                json("""{"results":[{"id":"${e.id}","status":"applied","version":4}]}""")
            } else json(emptyPull(4))
        }

        val result = SyncEngine(newApi(engine), store).sync()

        assertEquals(SyncResult.Done(pushed = 1, pulled = 0, failed = 0), result)
        assertEquals(listOf(e.id), request!!.changes.map { it.id })
        val saved = store.get(e.id)!!
        assertFalse(saved.dirty)
        assertEquals(4, saved.version)
        assertEquals(4, store.cursor())
    }

    @Test
    fun pullStoresRemoteEntriesAndAdvancesTheCursor() = runTest {
        val store = newStore()
        val engine = MockEngine {
            json(
                """{"changes":[${change("r1", "From phone", "2026-02-01T00:00:00Z", 3)}],""" +
                    """"next_cursor":3,"has_more":false}""",
            )
        }

        val result = SyncEngine(newApi(engine), store).sync()

        assertEquals(SyncResult.Done(0, 1, 0), result)
        val e = store.get("r1")!!
        assertEquals("From phone", e.title)
        assertFalse(e.dirty)
        assertEquals(3, store.cursor())
    }

    @Test
    fun pullFollowsPagesUntilNoMoreAndSendsTheCursor() = runTest {
        val store = newStore()
        val sinceValues = mutableListOf<String?>()
        val engine = MockEngine { r ->
            val since = r.url.parameters["since"]
            sinceValues += since
            if (since == "0") {
                json("""{"changes":[${change("a", "A", "2026-02-01T00:00:00Z", 1)}],"next_cursor":1,"has_more":true}""")
            } else {
                json("""{"changes":[${change("b", "B", "2026-02-02T00:00:00Z", 2)}],"next_cursor":2,"has_more":false}""")
            }
        }

        val result = SyncEngine(newApi(engine), store).sync()

        assertEquals(SyncResult.Done(0, 2, 0), result)
        assertEquals(listOf("0", "1"), sinceValues)
        assertEquals(2, store.cursor())
    }

    @Test
    fun newerLocalEditSurvivesAnOlderRemoteCopy() = runTest {
        val store = newStore()
        val local = store.create("Local", "x") // dirty, updatedAt ~1_000_000 ms
        val engine = MockEngine { r ->
            if (r.url.encodedPath == "/sync/push") {
                json("""{"results":[{"id":"${local.id}","status":"stale"}]}""")
            } else {
                // server copy is OLDER than the local edit (1970 vs 1970+1000s)
                json("""{"changes":[${change(local.id, "Server", "1970-01-01T00:00:00Z", 2)}],"next_cursor":2,"has_more":false}""")
            }
        }

        SyncEngine(newApi(engine), store).sync()

        val e = store.get(local.id)!!
        assertEquals("Local", e.title)
        assertTrue(e.dirty, "still waiting to be pushed")
    }

    @Test
    fun newerRemoteCopyReplacesAStaleLocalEdit() = runTest {
        val store = newStore()
        val local = store.create("Local", "x")
        val engine = MockEngine { r ->
            if (r.url.encodedPath == "/sync/push") {
                json("""{"results":[{"id":"${local.id}","status":"stale"}]}""")
            } else {
                json("""{"changes":[${change(local.id, "Server", "2030-01-01T00:00:00Z", 9)}],"next_cursor":9,"has_more":false}""")
            }
        }

        SyncEngine(newApi(engine), store).sync()

        val e = store.get(local.id)!!
        assertEquals("Server", e.title)
        assertFalse(e.dirty)
    }

    @Test
    fun remoteDeleteBecomesALocalTombstone() = runTest {
        val store = newStore()
        val engine = MockEngine {
            json("""{"changes":[${change("r1", "", "2026-03-01T00:00:00Z", 5, deleted = "2026-03-01T00:00:00Z")}],"next_cursor":5,"has_more":false}""")
        }

        SyncEngine(newApi(engine), store).sync()

        assertTrue(store.list().isEmpty())
        assertTrue(store.get("r1")!!.deletedAt != null)
    }

    @Test
    fun offlineSyncFailsButKeepsLocalChangesDirty() = runTest {
        val store = newStore()
        val e = store.create("Offline note", "x")
        val engine = MockEngine { throw RuntimeException("no network") }

        val result = SyncEngine(newApi(engine), store).sync()

        assertTrue(result is SyncResult.Failed)
        assertTrue(store.get(e.id)!!.dirty)
        assertEquals(0, store.cursor())
    }

    @Test
    fun invalidChangeIsDroppedInsteadOfRetriedForever() = runTest {
        val store = newStore()
        val e = store.create("Bad", "x")
        val engine = MockEngine { r ->
            if (r.url.encodedPath == "/sync/push") {
                json("""{"results":[{"id":"${e.id}","status":"invalid","error":"title too long"}]}""")
            } else json(emptyPull())
        }

        val result = SyncEngine(newApi(engine), store).sync()

        assertEquals(SyncResult.Done(0, 0, 1), result)
        assertFalse(store.get(e.id)!!.dirty)
    }

    @Test
    fun clearWipesEntriesAndCursor() = runTest {
        val store = newStore()
        store.create("a", "b")
        store.setCursor(7)
        store.clear()
        assertTrue(store.list().isEmpty())
        assertEquals(0, store.cursor())
        assertNull(store.get("anything"))
    }
}
