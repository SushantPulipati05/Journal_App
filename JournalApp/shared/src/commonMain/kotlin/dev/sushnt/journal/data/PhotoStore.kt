@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package dev.sushnt.journal.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
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

/** What the device knows about one photo. */
data class Photo(
    val id: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    /** The image file is on this device. false = still to download from the server. */
    val haveFile: Boolean,
    /** The server has the file. false = still to upload. */
    val uploaded: Boolean,
    /** Removed here; the server still has to hear about it. */
    val deleted: Boolean,
    val createdAt: Long,
    /** When no entry used this photo any more (see [PhotoStore.collectGarbage]). */
    val orphanSince: Long?,
)

/**
 * The photos on this device: a small SQLite table (photos.db) plus one JPEG file per
 * photo in <dataDir>/photos. Works fully offline; [PhotoSync] uploads/downloads later.
 */
class PhotoStore(
    private val connection: SQLiteConnection,
    private val dir: String,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private val _revision = MutableStateFlow(0L)

    /** Goes up whenever a photo file arrives or goes away, so the screens can redraw. */
    val revision: StateFlow<Long> = _revision

    /** Recently drawn photos, so scrolling back up doesn't decode them again. */
    /** Key "id@size". Ordered oldest-used first, so the oldest are dropped when it's full. */
    private val cache = LinkedHashMap<String, ImageBitmap>()
    private var cacheBytes = 0L
    private val cacheLock = Mutex()

    init {
        makeDirs(dir)
        connection.exec(
            """CREATE TABLE IF NOT EXISTS photos (
                id TEXT NOT NULL PRIMARY KEY,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                size_bytes INTEGER NOT NULL,
                have_file INTEGER NOT NULL,
                uploaded INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                orphan_since INTEGER
            )""",
        )
        connection.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT NOT NULL PRIMARY KEY, value INTEGER NOT NULL)")
    }

    private fun path(id: String) = "$dir/$id.jpg"

    // ---------- what the screens use ----------

    /** Shrinks the picked image and keeps it on this device. Returns the new photo's id, or null if it isn't an image. */
    suspend fun add(original: ByteArray): Photo? {
        val prepared = withContext(Dispatchers.Default) { preparePhoto(original) } ?: return null
        val photo = Photo(
            id = Uuid.random().toString(),
            width = prepared.width,
            height = prepared.height,
            sizeBytes = prepared.bytes.size.toLong(),
            haveFile = true,
            uploaded = false,
            deleted = false,
            createdAt = now(),
            orphanSince = null,
        )
        locked {
            writeFileBytes(path(photo.id), prepared.bytes)
            upsert(photo)
        }
        bump()
        return photo
    }

    suspend fun get(id: String): Photo? = locked { getUnlocked(id) }

    /**
     * A copy of a photo under a new id (used when restoring an entry from the Trash: the old
     * id is deleted on the server and can't be reused). null if the file isn't on this device.
     */
    suspend fun duplicate(id: String): String? {
        val copy = locked {
            val old = getUnlocked(id) ?: return@locked null
            val bytes = readFileBytes(path(id)) ?: return@locked null
            val fresh = old.copy(id = Uuid.random().toString(), uploaded = false, deleted = false, haveFile = true, createdAt = now(), orphanSince = null)
            writeFileBytes(path(fresh.id), bytes)
            upsert(fresh)
            fresh.id
        }
        if (copy != null) bump()
        return copy
    }

    /**
     * The decoded image, or null while the file isn't on this device (yet).
     * [maxSide]: unpack at about this many pixels on the long side (thumbnails, covers);
     * null = full size (photos shown large inside an entry).
     */
    suspend fun image(id: String, maxSide: Int? = null): ImageBitmap? {
        val key = "$id@${maxSide ?: 0}"
        cacheLock.withLock {
            cache.remove(key)?.let { hit ->
                cache[key] = hit // move to the "recently used" end
                return hit
            }
        }
        val bitmap = withContext(Dispatchers.Default) {
            readFileBytes(path(id))?.let { decodeImage(it, maxSide) }
        } ?: return null
        cacheLock.withLock {
            cache.put(key, bitmap)?.let { cacheBytes -= it.byteSize() }
            cacheBytes += bitmap.byteSize()
            // Keep memory use bounded by SIZE, not by count: drop the least recently used.
            while (cacheBytes > CACHE_BYTES && cache.size > 1) {
                val oldest = cache.keys.first()
                cache.remove(oldest)?.let { cacheBytes -= it.byteSize() }
            }
        }
        return bitmap
    }

    private fun ImageBitmap.byteSize(): Long = width.toLong() * height * 4

    private fun dropFromCache(id: String) {
        val keys = cache.keys.filter { it.startsWith("$id@") }
        for (k in keys) cache.remove(k)?.let { cacheBytes -= it.byteSize() }
    }

    // ---------- what PhotoSync uses ----------

    suspend fun all(): List<Photo> = locked { rows("SELECT $COLS FROM photos") }

    suspend fun fileBytes(id: String): ByteArray? = locked { readFileBytes(path(id)) }

    suspend fun markUploaded(id: String) = locked {
        connection.exec("UPDATE photos SET uploaded = 1 WHERE id = ?") { it.bindText(1, id) }
    }

    /** A photo another device added: remember it; the file is downloaded later. */
    suspend fun addRemote(id: String, width: Int, height: Int, sizeBytes: Long) = locked {
        if (getUnlocked(id) == null) {
            upsert(Photo(id, width, height, sizeBytes, haveFile = false, uploaded = true, deleted = false, createdAt = now(), orphanSince = null))
        }
    }

    suspend fun saveDownloaded(id: String, bytes: ByteArray) {
        locked {
            writeFileBytes(path(id), bytes)
            connection.exec("UPDATE photos SET have_file = 1 WHERE id = ?") { it.bindText(1, id) }
        }
        bump()
    }

    /** Gone everywhere (deleted on another device, or the server confirmed our delete). */
    suspend fun forget(id: String) {
        locked {
            deleteFileAt(path(id))
            connection.exec("DELETE FROM photos WHERE id = ?") { it.bindText(1, id) }
        }
        cacheLock.withLock { dropFromCache(id) }
        bump()
    }

    /**
     * Photos that no entry uses any more are deleted, but only after a minute, so
     * "Undo" right after removing a photo always works. [used] = ids in live entries.
     */
    suspend fun collectGarbage(used: Set<String>) {
        val t = now()
        val gone = mutableListOf<String>()
        locked {
            for (p in rows("SELECT $COLS FROM photos WHERE deleted = 0")) {
                when {
                    p.id in used -> if (p.orphanSince != null) {
                        connection.exec("UPDATE photos SET orphan_since = NULL WHERE id = ?") { it.bindText(1, p.id) }
                    }
                    // Just added and its entry isn't saved yet: leave it alone.
                    t - p.createdAt < ORPHAN_GRACE_MS -> {}
                    p.orphanSince == null -> connection.exec("UPDATE photos SET orphan_since = ? WHERE id = ?") {
                        it.bindLong(1, t)
                        it.bindText(2, p.id)
                    }
                    t - p.orphanSince >= ORPHAN_GRACE_MS -> gone += p.id
                }
            }
            for (id in gone) {
                deleteFileAt(path(id))
                // Uploaded ones stay as "deleted" until the server has been told.
                connection.exec("UPDATE photos SET deleted = 1, have_file = 0 WHERE id = ? AND uploaded = 1") { it.bindText(1, id) }
                connection.exec("DELETE FROM photos WHERE id = ? AND uploaded = 0") { it.bindText(1, id) }
            }
        }
        cacheLock.withLock { gone.forEach { dropFromCache(it) } }
        if (gone.isNotEmpty()) bump()
    }

    suspend fun cursor(): Long = locked {
        connection.queryRows("SELECT value FROM meta WHERE key = 'cursor'") { it.getLong(0) }.firstOrNull() ?: 0L
    }

    suspend fun setCursor(value: Long) = locked {
        connection.exec("INSERT OR REPLACE INTO meta (key, value) VALUES ('cursor', ?)") { it.bindLong(1, value) }
    }

    /** Sign-out: remove every photo from this device. */
    suspend fun clear() {
        locked {
            for (p in rows("SELECT $COLS FROM photos")) deleteFileAt(path(p.id))
            connection.exec("DELETE FROM photos")
            connection.exec("DELETE FROM meta")
        }
        cacheLock.withLock {
            cache.clear()
            cacheBytes = 0
        }
        bump()
    }

    // ---------- internals ----------

    private suspend fun <T> locked(block: () -> T): T =
        lock.withLock { withContext(Dispatchers.Default) { block() } }

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    private fun getUnlocked(id: String): Photo? =
        rows("SELECT $COLS FROM photos WHERE id = ?") { it.bindText(1, id) }.firstOrNull()

    private fun rows(sql: String, bind: (androidx.sqlite.SQLiteStatement) -> Unit = {}): List<Photo> =
        connection.queryRows(sql, bind) {
            Photo(
                id = it.getText(0),
                width = it.getLong(1).toInt(),
                height = it.getLong(2).toInt(),
                sizeBytes = it.getLong(3),
                haveFile = it.getLong(4) != 0L,
                uploaded = it.getLong(5) != 0L,
                deleted = it.getLong(6) != 0L,
                createdAt = it.getLong(7),
                orphanSince = if (it.isNull(8)) null else it.getLong(8),
            )
        }

    private fun upsert(p: Photo) {
        connection.exec(
            """INSERT OR REPLACE INTO photos ($COLS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ) {
            it.bindText(1, p.id)
            it.bindLong(2, p.width.toLong())
            it.bindLong(3, p.height.toLong())
            it.bindLong(4, p.sizeBytes)
            it.bindLong(5, if (p.haveFile) 1 else 0)
            it.bindLong(6, if (p.uploaded) 1 else 0)
            it.bindLong(7, if (p.deleted) 1 else 0)
            it.bindLong(8, p.createdAt)
            if (p.orphanSince == null) it.bindNull(9) else it.bindLong(9, p.orphanSince)
        }
    }

    companion object {
        private const val COLS = "id, width, height, size_bytes, have_file, uploaded, deleted, created_at, orphan_since"
        private const val ORPHAN_GRACE_MS = 60_000L
        /** At most ~48 MB of unpacked photos in memory (was: up to 40 full photos, ~300 MB). */
        private const val CACHE_BYTES = 48L * 1024 * 1024

        fun open(driver: SQLiteDriver, dataDir: String) =
            PhotoStore(driver.open("$dataDir/photos.db"), "$dataDir/photos")
    }
}
