@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package dev.sushnt.journal.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One journal entry as stored on this device. Times are epoch milliseconds.
 *
 * [dirty] means "changed here, not yet confirmed by the server".
 * A non-null [deletedAt] is a tombstone: the entry is gone for the user, but
 * the row stays until the server has heard about it.
 */
data class Entry(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val dirty: Boolean = false,
    val version: Long = 0,
    /**
     * The text as the server last had it (what this device's edits started from).
     * Lets a sync MERGE edits made on two devices instead of one wiping the other.
     * null = never synced.
     */
    val baseTitle: String? = null,
    val baseBody: String? = null,
)

/** A deleted entry kept in the Trash for 30 days (on this device). */
data class TrashedEntry(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val deletedAt: Long,
)

/** What happened when a change from the server was applied. */
enum class ApplyResult { Stored, KeptLocal, Merged }

/**
 * The device's own copy of the journal, in a SQLite file. The app reads and writes
 * only this (so it works offline); [SyncEngine] reconciles it with the server.
 *
 * All access goes through one lock because a SQLite connection must not be used by
 * two coroutines at the same time.
 */
class EntryStore(
    private val connection: SQLiteConnection,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: () -> String = { Uuid.random().toString() },
    // Disk work runs off the UI thread so scrolling never stutters.
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lock = Mutex()
    private val _revision = MutableStateFlow(0L)

    /** Goes up after every write. Screens watch it to know when to reload. */
    val revision: StateFlow<Long> = _revision

    init {
        migrate()
    }

    // ---------- what the screens use ----------

    /** Newest first, without deleted entries. */
    suspend fun list(): List<Entry> = locked {
        entries("SELECT $COLUMNS FROM entries WHERE deleted_at IS NULL ORDER BY created_at DESC, id")
    }

    suspend fun get(id: String): Entry? = locked { getUnlocked(id) }

    suspend fun create(title: String, body: String): Entry = locked {
        val t = now()
        val entry = Entry(newId(), title, body, createdAt = t, updatedAt = t, dirty = true)
        upsert(entry)
        bump()
        entry
    }

    /** Returns false if the entry doesn't exist or was deleted. */
    suspend fun update(id: String, title: String, body: String): Boolean = locked {
        val old = getUnlocked(id)
        if (old == null || old.deletedAt != null) return@locked false
        // updated_at must always grow, or "latest edit wins" could pick the wrong one
        val t = maxOf(now(), old.updatedAt + 1)
        upsert(old.copy(title = title, body = body, updatedAt = t, dirty = true))
        bump()
        true
    }

    /** Marks as deleted and erases the text right away; only a tombstone is kept. */
    suspend fun delete(id: String): Boolean = locked {
        val old = getUnlocked(id)
        if (old == null || old.deletedAt != null) return@locked false
        val t = maxOf(now(), old.updatedAt + 1)
        putInTrash(old, t)
        upsert(old.copy(title = "", body = "", updatedAt = t, deletedAt = t, dirty = true))
        bump()
        true
    }

    // ---------- trash ----------

    /** Deleted entries, newest first. Ones older than 30 days are removed for good first. */
    suspend fun trash(): List<TrashedEntry> = locked {
        run("DELETE FROM trash WHERE deleted_at < ?") { it.bindLong(1, now() - TRASH_DAYS * DAY_MS) }
        query("SELECT id, title, body, created_at, deleted_at FROM trash ORDER BY deleted_at DESC") {
            TrashedEntry(it.getText(0), it.getText(1), it.getText(2), it.getLong(3), it.getLong(4))
        }
    }

    /**
     * Brings a deleted entry back as a NEW entry (same text and date). The old one stays
     * deleted on the server; a fresh id syncs cleanly to every device.
     */
    suspend fun restoreFromTrash(trashId: String, title: String, body: String): Entry? = locked {
        val item = query("SELECT created_at FROM trash WHERE id = ?", { it.bindText(1, trashId) }) { it.getLong(0) }.firstOrNull()
            ?: return@locked null
        val t = now()
        val entry = Entry(newId(), title, body, createdAt = item, updatedAt = t, dirty = true)
        upsert(entry)
        run("DELETE FROM trash WHERE id = ?") { it.bindText(1, trashId) }
        bump()
        entry
    }

    suspend fun deleteForever(trashId: String) = locked {
        run("DELETE FROM trash WHERE id = ?") { it.bindText(1, trashId) }
        bump()
    }

    suspend fun emptyTrash() = locked {
        run("DELETE FROM trash") {}
        bump()
    }

    /** Photo ids used by entries in the Trash, so their files are kept until the Trash lets go. */
    suspend fun trashPhotoIds(): Set<String> = locked {
        query("SELECT body FROM trash") { it.getText(0) }.flatMap { photoIds(it) }.toSet()
    }

    // ---------- what the sync engine uses ----------

    suspend fun dirtyEntries(): List<Entry> = locked {
        entries("SELECT $COLUMNS FROM entries WHERE dirty = 1 ORDER BY updated_at, id")
    }

    /**
     * The server accepted what we sent. Only clears the dirty flag if the entry is still
     * exactly what we sent, so an edit made while the push was in flight is not lost.
     */
    suspend fun markSynced(id: String, sentUpdatedAt: Long, version: Long) = locked {
        run("UPDATE entries SET dirty = 0, version = ?, base_title = title, base_body = body WHERE id = ? AND updated_at = ?") {
            it.bindLong(1, version)
            it.bindText(2, id)
            it.bindLong(3, sentUpdatedAt)
        }
    }

    /**
     * Applies a change that came from the server.
     *  - No unsent edit here: the server's copy simply replaces ours.
     *  - Unsent edit here AND the other device changed it too: the two are MERGED line by
     *    line (see mergeText), so neither device's writing is lost. The merged text is then
     *    pushed back to the server.
     */
    suspend fun applyRemote(remote: Entry): ApplyResult = locked {
        val local = getUnlocked(remote.id)
        val synced = remote.copy(dirty = false, baseTitle = remote.title, baseBody = remote.body)

        // Deleted on another device: keep a copy in this device's Trash first.
        if (remote.deletedAt != null && local != null && local.deletedAt == null &&
            (local.title.isNotBlank() || local.body.isNotBlank())
        ) {
            putInTrash(local, remote.deletedAt)
        }

        if (local == null || !local.dirty) {
            upsert(synced)
            bump()
            return@locked ApplyResult.Stored
        }

        // Deletes, and entries that never synced: the most recent change wins (as before).
        if (remote.deletedAt != null || local.deletedAt != null || local.baseBody == null || local.baseTitle == null) {
            if (local.updatedAt > remote.updatedAt) return@locked ApplyResult.KeptLocal
            upsert(synced)
            bump()
            return@locked ApplyResult.Stored
        }

        val base = joinText(local.baseTitle, local.baseBody)
        val mine = joinText(local.title, local.body)
        val theirs = joinText(remote.title, remote.body)
        val pushAfter = maxOf(now(), remote.updatedAt + 1, local.updatedAt + 1)
        when {
            // The server hasn't changed since we started: our edit just has to win the push.
            theirs == base -> {
                upsert(local.copy(updatedAt = pushAfter, version = remote.version))
                ApplyResult.KeptLocal
            }
            // We hadn't really changed anything (or made the same change): take theirs.
            mine == base || mine == theirs -> {
                upsert(synced)
                bump()
                ApplyResult.Stored
            }
            // Both changed it: merge.
            else -> {
                val (title, body) = splitText(mergeText(base, mine, theirs))
                upsert(
                    local.copy(
                        title = title,
                        body = body,
                        updatedAt = pushAfter,
                        dirty = true,
                        version = remote.version,
                        baseTitle = remote.title,
                        baseBody = remote.body,
                    ),
                )
                bump()
                ApplyResult.Merged
            }
        }
    }

    suspend fun cursor(): Long = locked {
        query("SELECT value FROM meta WHERE key = 'cursor'") { it.getLong(0) }.firstOrNull() ?: 0L
    }

    suspend fun setCursor(cursor: Long) = locked {
        run("INSERT OR REPLACE INTO meta (key, value) VALUES ('cursor', ?)") { it.bindLong(1, cursor) }
    }

    /** Wipes everything. Used on sign-out so the next account never sees these entries. */
    suspend fun clear() = locked {
        run("DELETE FROM entries") {}
        run("DELETE FROM meta") {}
        run("DELETE FROM trash") {}
        bump()
    }

    fun close() = connection.close()

    // ---------- internals (call only while holding the lock) ----------

    private suspend fun <T> locked(block: () -> T): T =
        lock.withLock { withContext(dispatcher) { block() } }

    private fun migrate() {
        val version = query("PRAGMA user_version") { it.getLong(0) }.firstOrNull() ?: 0L
        if (version < 1) {
            run(
                """CREATE TABLE IF NOT EXISTS entries (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    dirty INTEGER NOT NULL DEFAULT 0,
                    version INTEGER NOT NULL DEFAULT 0
                )""",
            ) {}
            run("CREATE INDEX IF NOT EXISTS idx_entries_dirty ON entries (dirty)") {}
            run("CREATE TABLE IF NOT EXISTS meta (key TEXT NOT NULL PRIMARY KEY, value INTEGER NOT NULL)") {}
            run("PRAGMA user_version = 1") {}
        }
        if (version < 2) {
            // v2: remember the last synced text (for merging), and a Trash.
            run("ALTER TABLE entries ADD COLUMN base_title TEXT") {}
            run("ALTER TABLE entries ADD COLUMN base_body TEXT") {}
            // Entries already synced: what they hold now is what the server has.
            run("UPDATE entries SET base_title = title, base_body = body WHERE dirty = 0") {}
            run(
                """CREATE TABLE IF NOT EXISTS trash (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    deleted_at INTEGER NOT NULL
                )""",
            ) {}
            run("PRAGMA user_version = 2") {}
        }
        // Future schema changes go here as: if (version < 3) { ... PRAGMA user_version = 3 }
    }

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    private fun getUnlocked(id: String): Entry? =
        entries("SELECT $COLUMNS FROM entries WHERE id = ?") { it.bindText(1, id) }.firstOrNull()

    private fun upsert(e: Entry) {
        run(
            """INSERT OR REPLACE INTO entries
               (id, title, body, created_at, updated_at, deleted_at, dirty, version, base_title, base_body)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ) {
            it.bindText(1, e.id)
            it.bindText(2, e.title)
            it.bindText(3, e.body)
            it.bindLong(4, e.createdAt)
            it.bindLong(5, e.updatedAt)
            if (e.deletedAt == null) it.bindNull(6) else it.bindLong(6, e.deletedAt)
            it.bindLong(7, if (e.dirty) 1 else 0)
            it.bindLong(8, e.version)
            if (e.baseTitle == null) it.bindNull(9) else it.bindText(9, e.baseTitle)
            if (e.baseBody == null) it.bindNull(10) else it.bindText(10, e.baseBody)
        }
    }

    private fun putInTrash(e: Entry, deletedAt: Long) {
        if (e.title.isBlank() && e.body.isBlank()) return // nothing worth keeping
        run("INSERT OR REPLACE INTO trash (id, title, body, created_at, deleted_at) VALUES (?, ?, ?, ?, ?)") {
            it.bindText(1, e.id)
            it.bindText(2, e.title)
            it.bindText(3, e.body)
            it.bindLong(4, e.createdAt)
            it.bindLong(5, deletedAt)
        }
    }

    private fun run(sql: String, bind: (SQLiteStatement) -> Unit) {
        val st = connection.prepare(sql)
        try {
            bind(st)
            st.step()
        } finally {
            st.close()
        }
    }

    private fun <T> query(sql: String, bind: (SQLiteStatement) -> Unit = {}, read: (SQLiteStatement) -> T): List<T> {
        val st = connection.prepare(sql)
        try {
            bind(st)
            val out = mutableListOf<T>()
            while (st.step()) out += read(st)
            return out
        } finally {
            st.close()
        }
    }

    private fun entries(sql: String, bind: (SQLiteStatement) -> Unit = {}): List<Entry> =
        query(sql, bind) { it.toEntry() }

    private fun SQLiteStatement.toEntry() = Entry(
        id = getText(0),
        title = getText(1),
        body = getText(2),
        createdAt = getLong(3),
        updatedAt = getLong(4),
        deletedAt = if (isNull(5)) null else getLong(5),
        dirty = getLong(6) != 0L,
        version = getLong(7),
        baseTitle = if (isNull(8)) null else getText(8),
        baseBody = if (isNull(9)) null else getText(9),
    )

    companion object {
        private const val COLUMNS = "id, title, body, created_at, updated_at, deleted_at, dirty, version, base_title, base_body"
        private const val TRASH_DAYS = 30L
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** One block of text, first line = title (same rule as the Write page). */
        private fun joinText(title: String, body: String) = if (body.isEmpty()) title else "$title\n$body"

        private fun splitText(text: String): Pair<String, String> {
            val first = text.substringBefore('\n')
            val rest = if ('\n' in text) text.substringAfter('\n') else ""
            return first.trim() to rest
        }

        /** [path] is a file path, or ":memory:" for a throwaway database (tests). */
        fun open(
            driver: SQLiteDriver,
            path: String,
            now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
            newId: () -> String = { Uuid.random().toString() },
        ) = EntryStore(driver.open(path), now, newId)
    }
}
