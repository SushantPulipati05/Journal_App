@file:OptIn(ExperimentalTime::class)

package dev.sushnt.journal.data

import dev.sushnt.journal.api.ApiError
import dev.sushnt.journal.api.ApiResult
import dev.sushnt.journal.api.JournalApi
import dev.sushnt.journal.api.SyncChangeDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

sealed interface SyncResult {
    /** [pushed] local changes accepted, [pulled] server changes stored, [failed] changes the server refused for good. */
    data class Done(val pushed: Int, val pulled: Int, val failed: Int) : SyncResult
    data class Failed(val error: ApiError) : SyncResult
}

/**
 * Makes the device's copy and the server's copy agree. Safe to call any time,
 * as often as you like, including while offline (it just reports Failed).
 *
 *  1. PUSH: send every entry changed on this device.
 *  2. PULL: fetch everything the server changed since our bookmark (the cursor).
 *
 * Conflicts: the most recent edit wins, decided by updated_at.
 */
class SyncEngine(
    private val api: JournalApi,
    private val store: EntryStore,
    private val batchSize: Int = 100,
) {
    // Two syncs at once would push the same entries twice; make them take turns.
    private val lock = Mutex()

    suspend fun sync(): SyncResult = lock.withLock {
        var pushed = 0
        var failed = 0

        // ----- push -----
        for (batch in store.dirtyEntries().chunked(batchSize)) {
            when (val result = api.push(batch.map { it.toDto() })) {
                is ApiResult.Err -> return SyncResult.Failed(result.error)
                is ApiResult.Ok -> {
                    val sent = batch.associateBy { it.id }
                    for (r in result.value.results) {
                        val entry = sent[r.id] ?: continue
                        when (r.status) {
                            "applied" -> {
                                store.markSynced(entry.id, entry.updatedAt, r.version)
                                pushed++
                            }
                            // The server has something newer. Stay dirty; the pull below
                            // brings the newer copy and applyRemote decides.
                            "stale" -> {}
                            // "rejected" / "invalid": retrying can never succeed, so stop sending it.
                            else -> {
                                store.markSynced(entry.id, entry.updatedAt, entry.version)
                                failed++
                            }
                        }
                    }
                }
            }
        }

        // ----- pull -----
        var pulled = 0
        var cursor = store.cursor()
        while (true) {
            when (val result = api.pull(cursor, batchSize)) {
                is ApiResult.Err -> return SyncResult.Failed(result.error)
                is ApiResult.Ok -> {
                    val page = result.value
                    for (change in page.changes) {
                        val entry = change.toEntryOrNull() ?: continue
                        if (store.applyRemote(entry)) pulled++
                    }
                    // Save the bookmark after every page so an interruption loses little.
                    store.setCursor(page.nextCursor)
                    if (!page.hasMore || page.nextCursor <= cursor) break
                    cursor = page.nextCursor
                }
            }
        }
        SyncResult.Done(pushed, pulled, failed)
    }
}

private fun Entry.toDto() = SyncChangeDto(
    id = id,
    title = title,
    body = body,
    createdAt = Instant.fromEpochMilliseconds(createdAt).toString(),
    updatedAt = Instant.fromEpochMilliseconds(updatedAt).toString(),
    deletedAt = deletedAt?.let { Instant.fromEpochMilliseconds(it).toString() },
)

/** Returns null for a change we can't understand, so one bad record never blocks the rest. */
private fun SyncChangeDto.toEntryOrNull(): Entry? = try {
    Entry(
        id = id,
        title = title,
        body = body,
        createdAt = Instant.parse(createdAt).toEpochMilliseconds(),
        updatedAt = Instant.parse(updatedAt).toEpochMilliseconds(),
        deletedAt = deletedAt?.let { Instant.parse(it).toEpochMilliseconds() },
        dirty = false,
        version = version,
    )
} catch (_: IllegalArgumentException) {
    null
}
