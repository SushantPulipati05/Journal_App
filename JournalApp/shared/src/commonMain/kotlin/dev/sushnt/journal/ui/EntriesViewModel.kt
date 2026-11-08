package dev.sushnt.journal.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sushnt.journal.data.Entry
import dev.sushnt.journal.data.EntryStore
import dev.sushnt.journal.data.SyncController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** One day in the Journals list: "Today", "Yesterday", "3 Nov"... */
data class DaySection(val header: String, val entries: List<Entry>)

/** The Journals tab: every entry, grouped by day, with search and the streak. */
class EntriesViewModel(
    private val store: EntryStore,
    private val syncController: SyncController,
) : ViewModel() {

    /** An entry swiped away but not deleted yet, so "Undo" can still bring it back. */
    private val _pendingDelete = MutableStateFlow<Entry?>(null)
    val pendingDelete: StateFlow<Entry?> = _pendingDelete
    private var deleteJob: Job? = null
    private var syncJob: Job? = null

    /** Every entry, newest first (minus one waiting to be deleted). Reloads on every database change. */
    val entries: StateFlow<List<Entry>> = combine(store.revision.map { store.list() }, _pendingDelete) { list, pending ->
        if (pending == null) list else list.filter { it.id != pending.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** What the list shows: matching entries, grouped by the day they were written. */
    val sections: StateFlow<List<DaySection>> = combine(entries, _query) { list, q ->
        val words = q.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        val matching = if (words.isEmpty()) list else list.filter { e ->
            val text = (e.title + " " + e.body).lowercase()
            words.all { it in text }
        }
        val now = nowMillis()
        matching
            .groupBy { localDate(it.createdAt) }
            .entries
            .sortedByDescending { it.key }
            .map { (day: LocalDate, dayEntries) ->
                DaySection(dayHeader(day, now), dayEntries.sortedByDescending { it.createdAt })
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Days in a row with at least one entry. */
    val streak: StateFlow<Int> = entries
        .map { list -> streakDays(list.map { localDate(it.createdAt) }.toSet(), localDate(nowMillis())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val syncing: StateFlow<Boolean> = syncController.syncing
    val message: StateFlow<String?> = syncController.message

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
