package dev.sushnt.journal.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sushnt.journal.api.ApiError
import dev.sushnt.journal.data.Entry
import dev.sushnt.journal.data.EntryStore
import dev.sushnt.journal.data.SyncEngine
import dev.sushnt.journal.data.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Max title length the server accepts. */
const val MAX_TITLE = 200

class EntriesViewModel(
    private val store: EntryStore,
    private val sync: SyncEngine,
) : ViewModel() {

    /** Every entry. Reloads by itself whenever the database changes (local edit or sync). */
    val entries: StateFlow<List<Entry>> = store.revision
        .map { store.list() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** What the list shows: entries matching the search box (all of them when it's empty). */
    val visibleEntries: StateFlow<List<Entry>> = combine(entries, _query) { list, q ->
        val words = q.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) list
        else list.filter { e ->
            val text = (e.title + " " + e.body).lowercase()
            words.all { it in text }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    /** A short note for the user, e.g. "offline". null = nothing to say. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var syncAgain = false

    fun setQuery(q: String) {
        _query.value = q
    }

    /** Starts a sync. If one is running, another runs right after it, so no change is missed. */
    fun syncNow() {
        if (_syncing.value) {
            syncAgain = true
            return
        }
        viewModelScope.launch {
            _syncing.value = true
            do {
                syncAgain = false
                _message.value = when (val result = sync.sync()) {
                    is SyncResult.Done ->
                        if (result.failed > 0) "${result.failed} change(s) were refused by the server." else null
                    is SyncResult.Failed -> when (val e = result.error) {
                        is ApiError.Network -> "Offline. Your changes are saved here and will sync later."
                        ApiError.SessionExpired -> null // the app switches to the login screen by itself
                        is ApiError.Server -> "Sync failed (server error ${e.status})."
                    }
                }
            } while (syncAgain)
            _syncing.value = false
        }
    }

    /** id == null creates a new entry. Empty new entries are not saved. */
    fun save(id: String?, title: String, body: String) {
        viewModelScope.launch {
            val t = title.trim().take(MAX_TITLE)
            if (id == null) {
                if (t.isEmpty() && body.isBlank()) return@launch
                store.create(t, body)
            } else {
                val old = store.get(id) ?: return@launch
                if (old.title == t && old.body == body) return@launch // nothing changed
                store.update(id, t, body)
            }
            syncNow()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            if (store.delete(id)) syncNow()
        }
    }
}
