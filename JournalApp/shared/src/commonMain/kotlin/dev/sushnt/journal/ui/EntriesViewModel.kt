@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.sushnt.journal.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sushnt.journal.api.ApiError
import dev.sushnt.journal.api.ApiResult
import dev.sushnt.journal.api.JournalApi
import dev.sushnt.journal.api.SearchHitDto
import dev.sushnt.journal.data.Entry
import dev.sushnt.journal.data.EntryStore
import dev.sushnt.journal.data.PhotoStore
import dev.sushnt.journal.data.SearchHit
import dev.sushnt.journal.data.TrashedEntry
import dev.sushnt.journal.data.photoIds
import dev.sushnt.journal.data.SyncController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** One day in the Journals list: "Today", "Yesterday", "3 Nov"... */
data class DaySection(val header: String, val entries: List<Entry>)

/** An entry found by meaning, with the paragraph that matched. */
data class MeaningHit(val entry: Entry, val snippet: String, val score: Float)

/** "Similar in meaning" search, done by the server. */
sealed interface MeaningSearch {
    /** Query too short, or empty. */
    data object Idle : MeaningSearch
    data object Searching : MeaningSearch
    /** [pending]: entries still being prepared for search on the server. */
    data class Results(val hits: List<MeaningHit>, val pending: Int) : MeaningSearch
    /** Offline, or search isn't set up on the server. Word search still works. */
    data class Unavailable(val reason: String) : MeaningSearch
}

/** The Journals tab: every entry, grouped by day, with search and the streak. */
class EntriesViewModel(
    private val store: EntryStore,
    private val syncController: SyncController,
    private val photos: PhotoStore,
    private val api: JournalApi,
) : ViewModel() {

    /** An entry swiped away but not deleted yet, so "Undo" can still bring it back. */
    private val _pendingDelete = MutableStateFlow<Entry?>(null)
    val pendingDelete: StateFlow<Entry?> = _pendingDelete
    private var deleteJob: Job? = null
    private var syncJob: Job? = null

    /** Every entry, newest first (minus one waiting to be deleted). Reloads on every database change. */
    /** false until the entries have been read from the device once. */
    private val _loaded = MutableStateFlow(false)

    val entries: StateFlow<List<Entry>> = combine(store.revision.map { store.list() }.onEach { _loaded.value = true }, _pendingDelete) { list, pending ->
        if (pending == null) list else list.filter { it.id != pending.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Today's date, updated when the clock passes midnight. The streak and the "Today" /
     * "Yesterday" headers depend on it; without this, an app left open overnight (the
     * desktop app especially) kept showing yesterday's streak.
     */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(localDate(nowMillis()))
            delay(60_000)
        }
    }.distinctUntilChanged()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /**
     * Word search: the device's full-text index (SQLite FTS5) answers in milliseconds,
     * best matches first. Runs again after every change to the entries. null = no search.
     */
    private val wordHits: Flow<List<SearchHit>?> =
        combine(_query.map { it.trim() }.distinctUntilChanged(), store.revision) { q, _ -> q }
            .mapLatest { q -> if (q.isEmpty()) null else store.search(q) }

    /** Where the search words appear in each matching entry (entry id -> snippet). */
    val snippets: StateFlow<Map<String, String>> = wordHits
        .map { hits -> hits.orEmpty().associate { it.entry.id to it.snippet } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * What the list shows: every entry grouped by the day it was written or, while
     * searching, the matches with the best one first.
     */
    val sections: StateFlow<List<DaySection>> = combine(entries, wordHits, today) { list, hits, _ ->
        if (hits != null) {
            // Only entries that are still shown (not one waiting to be deleted).
            val shown = list.mapTo(HashSet<String>()) { it.id }
            val matches = hits.map { it.entry }.filter { it.id in shown }
            return@combine if (matches.isEmpty()) emptyList() else listOf(DaySection("Best matches", matches))
        }
        val now = nowMillis()
        list
            .groupBy { localDate(it.createdAt) }
            .entries
            .sortedByDescending { it.key }
            .map { (day: LocalDate, dayEntries) ->
                DaySection(dayHeader(day, now), dayEntries.sortedByDescending { it.createdAt })
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What the server answered for the current query (hits are entry ids). */
    private sealed interface RawMeaning {
        data object Idle : RawMeaning
        data object Searching : RawMeaning
        data class Hits(val hits: List<SearchHitDto>, val pending: Int) : RawMeaning
        data class Failed(val reason: String) : RawMeaning
    }

    private val rawMeaning: Flow<RawMeaning> = _query
        .map { it.trim() }
        .distinctUntilChanged()
        .transformLatest { q ->
            // transformLatest drops the old search as soon as you type more.
            if (q.length < MIN_MEANING_QUERY) {
                emit(RawMeaning.Idle)
                return@transformLatest
            }
            emit(RawMeaning.Searching)
            delay(500) // wait until you stop typing
            emit(
                when (val r = api.search(q)) {
                    is ApiResult.Ok -> RawMeaning.Hits(r.value.results, r.value.pending)
                    is ApiResult.Err -> RawMeaning.Failed(
                        when (r.error) {
                            is ApiError.Network -> "Search by meaning needs the internet."
                            else -> "Search by meaning isn't available right now."
                        },
                    )
                },
            )
        }

    /**
     * Entries similar in meaning to the query, besides the ones that contain its words
     * (those are already in [sections]). Only entries on this device are shown.
     */
    val meaning: StateFlow<MeaningSearch> = combine(rawMeaning, entries, sections) { raw, list, secs ->
        when (raw) {
            RawMeaning.Idle -> MeaningSearch.Idle
            RawMeaning.Searching -> MeaningSearch.Searching
            is RawMeaning.Failed -> MeaningSearch.Unavailable(raw.reason)
            is RawMeaning.Hits -> {
                val byId = list.associateBy { it.id }
                val wordMatches = secs.flatMapTo(HashSet<String>()) { s -> s.entries.map { it.id } }
                val hits = raw.hits.mapNotNull { h ->
                    byId[h.entryId]?.takeIf { it.id !in wordMatches }?.let { MeaningHit(it, h.snippet, h.score) }
                }
                // Keep the close ones: within a margin of the best match, at most 8.
                val best = hits.maxOfOrNull { it.score } ?: 0f
                MeaningSearch.Results(hits.filter { it.score >= best - MEANING_MARGIN }.take(8), raw.pending)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeaningSearch.Idle)

    /** Days in a row with at least one entry. */
    val streak: StateFlow<Int> = combine(entries, today) { list, day -> streakDays(list.map { localDate(it.createdAt) }.toSet(), day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val syncing: StateFlow<Boolean> = syncController.syncing

    /**
     * Show skeletons instead of "Nothing here yet": while the device copy is being read, and
     * on a fresh sign-in while the first sync is still downloading the entries.
     */
    val loading: StateFlow<Boolean> = combine(_loaded, entries, syncController.syncing, syncController.lastSyncedAt) { loaded, list, syncing, synced ->
        !loaded || (list.isEmpty() && syncing && synced == null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val message: StateFlow<String?> = syncController.message

    /** Just one entry, kept up to date (null once deleted). Much cheaper than [entries]. */
    fun watchEntry(id: String): Flow<Entry?> =
        combine(store.revision.map { store.get(id) }, _pendingDelete) { entry, pending ->
            entry?.takeIf { it.deletedAt == null && pending?.id != id }
        }

    /** Deleted entries (kept 30 days on this device), newest first. */
    val trash: StateFlow<List<TrashedEntry>> = store.revision.map { store.trash() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Puts a deleted entry back in Journals, photos included. */
    fun restore(item: TrashedEntry) {
        viewModelScope.launch {
            var body = item.body
            // The old photo ids were deleted on the server; give the photos new ones.
            for (id in photoIds(body).distinct()) {
                val copy = photos.duplicate(id) ?: continue
                body = body.replace("{{photo:$id", "{{photo:$copy")
            }
            if (store.restoreFromTrash(item.id, item.title, body) != null) syncController.syncNow()
        }
    }

    fun deleteForever(item: TrashedEntry) {
        viewModelScope.launch { store.deleteForever(item.id) }
    }

    fun emptyTrash() {
        viewModelScope.launch { store.emptyTrash() }
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun syncNow() = syncController.syncNow()

    /** Hide the entry now; really delete it after a few seconds unless [undoDelete] is called. */
    fun deleteWithUndo(entry: Entry) {
        commitPendingDelete() // only one undo at a time: the previous one becomes final
        _pendingDelete.value = entry
        deleteJob = viewModelScope.launch {
            delay(5_000)
            commitPendingDelete()
        }
    }

    fun undoDelete() {
        deleteJob?.cancel()
        _pendingDelete.value = null
    }

    /** Make a waiting delete final right away (also called when the app goes to the background). */
    fun commitPendingDelete() {
        val entry = _pendingDelete.value ?: return
        deleteJob?.cancel()
        _pendingDelete.value = null
        viewModelScope.launch {
            if (store.delete(entry.id)) syncController.syncNow()
        }
    }

    /** Saves edits made by tapping into an entry on the reading screen. */
    fun saveEdit(id: String, text: String) {
        if (text.isBlank()) return // never wipe an entry by accident; swipe to delete instead
        viewModelScope.launch {
            val entry = store.get(id) ?: return@launch
            if (entry.deletedAt != null) return@launch
            val (title, body) = splitEntryText(text)
            if (entry.title != title || entry.body != body) {
                store.update(id, title, body)
                syncJob?.cancel()
                syncJob = viewModelScope.launch {
                    delay(4_000)
                    syncController.syncNow()
                }
            }
        }
    }
}

/** Search by meaning starts at this many characters. */
private const val MIN_MEANING_QUERY = 3

/** How far below the best match a result may score and still be shown. */
private const val MEANING_MARGIN = 0.12f
