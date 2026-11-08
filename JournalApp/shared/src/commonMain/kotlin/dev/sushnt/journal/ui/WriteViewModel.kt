package dev.sushnt.journal.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sushnt.journal.data.Entry
import dev.sushnt.journal.data.EntryStore
import dev.sushnt.journal.data.PrefsStore
import dev.sushnt.journal.data.SyncController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SaveStatus { Idle, Saving, Saved }

/** How long after the last edit an entry still counts as "the one you were writing". */
private const val CONTINUE_WINDOW_MS = 30 * 60 * 1000L

/**
 * The Write tab: one blank page that saves itself.
 *
 * - Typing saves automatically a moment after you pause. There is no Save button.
 * - An empty page is never stored.
 * - Coming back within 30 minutes continues the same entry (if that setting is on).
 */
class WriteViewModel(
    private val store: EntryStore,
    private val syncController: SyncController,
    private val prefs: PrefsStore,
) : ViewModel() {

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    /** The stored entry this page is writing into; null until the first save. */
    private val _entryId = MutableStateFlow<String?>(null)
    val entryId: StateFlow<String?> = _entryId

    /** When this page's entry was started (shown at the top). */
    private val _startedAt = MutableStateFlow(nowMillis())
    val startedAt: StateFlow<Long> = _startedAt

    private val _status = MutableStateFlow(SaveStatus.Idle)
    val status: StateFlow<SaveStatus> = _status

    private var saveJob: Job? = null
    /** True from a keystroke until its save starts. Only then is it safe to cancel the wait. */
    private var pendingSave = false
    private var syncJob: Job? = null
    private val saveLock = Mutex()

    init {
        viewModelScope.launch { resumeRecentEntry() }
        // If our entry disappears (deleted on another device, or signed out), start fresh.
        viewModelScope.launch {
            store.revision.drop(1).collect {
                val id = _entryId.value ?: return@collect
                val entry = store.get(id)
                if (entry == null || entry.deletedAt != null) {
                    resetPage()
                } else if (!pendingSave && splitEntryText(_text.value) != (entry.title to entry.body)) {
                    // Changed somewhere else (reading screen, another device): show the new text.
                    _text.value = joinEntryText(entry.title, entry.body)
                }
            }
        }
    }

    fun onTextChange(newText: String) {
        if (newText == _text.value) return
        _text.value = newText
        _status.value = SaveStatus.Saving
        pendingSave = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800) // wait for a pause in typing
            save()
        }
    }

    /** Save right away, e.g. when leaving the tab or the app. */
    fun flush() {
        if (!pendingSave) return
        saveJob?.cancel()
        viewModelScope.launch { save() }
    }

    /** "New entry": keep what's written, start a blank page. */
    fun newEntry() {
        flush()
        resetPage()
    }

    /** "Edit" from the Journals tab: write into an existing entry. */
    fun open(entry: Entry) {
        flush()
        load(entry)
    }

    /** After signing out: forget the page completely (nothing is saved). */
    fun reset() {
        pendingSave = false
        saveJob?.cancel()
        syncJob?.cancel()
        resetPage()
    }

    private fun resetPage() {
        _text.value = ""
        _entryId.value = null
        _startedAt.value = nowMillis()
        _status.value = SaveStatus.Idle
    }

    private fun load(entry: Entry) {
        _text.value = joinEntryText(entry.title, entry.body)
        _entryId.value = entry.id
        _startedAt.value = entry.createdAt
        _status.value = SaveStatus.Saved
    }

    private suspend fun resumeRecentEntry() {
        if (!prefs.prefs.value.continueLastEntry) return
        val recent = store.list().maxByOrNull { it.updatedAt } ?: return
        val stillFresh = nowMillis() - recent.updatedAt < CONTINUE_WINDOW_MS
        // Only if the user hasn't started typing in the meantime.
        if (stillFresh && _text.value.isEmpty() && _entryId.value == null) load(recent)
    }

    private suspend fun save() = saveLock.withLock {
        pendingSave = false
        val text = _text.value
        val (title, body) = splitEntryText(text)
        val id = _entryId.value
        when {
            id == null && text.isBlank() -> {
                _status.value = SaveStatus.Idle // nothing to keep yet
                return@withLock
            }
            id == null -> _entryId.value = store.create(title, body).id
            text.isBlank() -> {
                // Everything was erased: an empty entry isn't worth keeping.
                store.delete(id)
                _entryId.value = null
                _status.value = SaveStatus.Idle
                scheduleSync()
                return@withLock
            }
            else -> {
                val existing = store.get(id)
                if (existing == null || existing.deletedAt != null) {
                    _entryId.value = store.create(title, body).id // it was deleted elsewhere: keep the words
                } else if (existing.title != title || existing.body != body) {
                    store.update(id, title, body)
                }
            }
        }
        if (_text.value == text) _status.value = SaveStatus.Saved
        scheduleSync()
    }

    /** Sync a few seconds after the last save, not on every keystroke. */
    private fun scheduleSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            delay(4_000)
            syncController.syncNow()
        }
    }
}
