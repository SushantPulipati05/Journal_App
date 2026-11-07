@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package dev.sushnt.journal.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
)

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
    suspend fun list(): List<Entry> = lock.withLock {
        entries("SELECT $COLUMNS FROM entries WHERE deleted_at IS NULL ORDER BY created_at DESC, id")
    }

    suspend fun get(id: String): Entry? = lock.withLock { getUnlocked(id) }

    suspend fun create(title: String, body: String): Entry = lock.withLock {
        val t = now()
        val entry = Entry(newId(), title, body, createdAt = t, updatedAt = t, dirty = true)
        upsert(entry)
        bump()
        entry
    }

    /** Returns false if the entry doesn't exist or was deleted. */
    suspend fun update(id: String, title: String, body: String): Boolean = lock.withLock {
        val old = getUnlocked(id)
        if (old == null || old.deletedAt != null) return@withLock false
        // updated_at must always grow, or "latest edit wins" could pick the wrong one
        val t = maxOf(now(), old.updatedAt + 1)
        upsert(old.copy(title = title, body = body, updatedAt = t, dirty = true))
        bump()
        true
    }

    /** Marks as deleted and erases the text right away; only a tombstone is kept. */
    suspend fun delete(id: String): Boolean = lock.withLock {
        val old = getUnlocked(id)
        if (old == null || old.deletedAt != null) return@withLock false
        val t = maxOf(now(), old.updatedAt + 1)
        upsert(old.copy(title = "", body = "", updatedAt = t, deletedAt = t, dirty = true))
        bump()
        true
    }

    // ---------- what the sync engine uses ----------

    suspend fun dirtyEntries(): List<Entry> = lock.withLock {
        entries("SELECT $COLUMNS FROM entries WHERE dirty = 1 ORDER BY updated_at, id")
    }

    /**
     * The server accepted what we sent. Only clears the dirty flag if the entry is still
     * exactly what we sent, so an edit made while the push was in flight is not lost.
     */
    suspend fun markSynced(id: String, sentUpdatedAt: Long, version: Long) = lock.withLock {
        run("UPDATE entries SET dirty = 0, version = ? WHERE id = ? AND updated_at = ?") {
            it.bindLong(1, version)
            it.bindText(2, id)
            it.bindLong(3, sentUpdatedAt)
        }
    }

    /**
     * Applies a change that came from the server. If we hold a newer unsent edit,
     * ours is kept (it will be pushed). Otherwise the server's copy replaces ours.
     * Returns true if the server's copy was stored.
     */
    suspend fun applyRemote(remote: Entry): Boolean = lock.withLock {
        val local = getUnlocked(remote.id)
        if (local != null && local.dirty && local.updatedAt > remote.updatedAt) return@withLock false
        upsert(remote.copy(dirty = false))
        bump()
        true
    }

    suspend fun cursor(): Long = lock.withLock {
        query("SELECT value FROM meta WHERE key = 'cursor'") { it.getLong(0) }.firstOrNull() ?: 0L
    }

    suspend fun setCursor(cursor: Long) = lock.withLock {
        run("INSERT OR REPLACE INTO meta (key, value) VALUES ('cursor', ?)") { it.bindLong(1, cursor) }
    }

    /** Wipes everything. Used on sign-out so the next account never sees these entries. */
    suspend fun clear() = lock.withLock {
        run("DELETE FROM entries") {}
        run("DELETE FROM meta") {}
        bump()
    }

    fun close() = connection.close()

    // ---------- internals (call only while holding the lock) ----------

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
        // Future schema changes go here as: if (version < 2) { ... PRAGMA user_version = 2 }
    }

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    private fun getUnlocked(id: String): Entry? =
        entries("SELECT $COLUMNS FROM entries WHERE id = ?") { it.bindText(1, id) }.firstOrNull()

    private fun upsert(e: Entry) {
        run(
            """INSERT OR REPLACE INTO entries
               (id, title, body, created_at, updated_at, deleted_at, dirty, version)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        ) {
            it.bindText(1, e.id)
            it.bindText(2, e.title)
            it.bindText(3, e.body)
            it.bindLong(4, e.createdAt)
            it.bindLong(5, e.updatedAt)
            if (e.deletedAt == null) it.bindNull(6) else it.bindLong(6, e.deletedAt)
            it.bindLong(7, if (e.dirty) 1 else 0)
            it.bindLong(8, e.version)
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
    )

    companion object {
        private const val COLUMNS = "id, title, body, created_at, updated_at, deleted_at, dirty, version"

        /** [path] is a file path, or ":memory:" for a throwaway database (tests). */
        fun open(
            driver: SQLiteDriver,
            path: String,
            now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
            newId: () -> String = { Uuid.random().toString() },
        ) = EntryStore(driver.open(path), now, newId)
    }
}
