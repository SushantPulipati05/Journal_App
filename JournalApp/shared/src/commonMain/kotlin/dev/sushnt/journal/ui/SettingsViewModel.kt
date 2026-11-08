package dev.sushnt.journal.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sushnt.journal.api.ApiError
import dev.sushnt.journal.api.ApiResult
import dev.sushnt.journal.data.AppPrefs
import dev.sushnt.journal.data.PrefsStore
import dev.sushnt.journal.data.SessionManager
import dev.sushnt.journal.data.SyncController
import dev.sushnt.journal.data.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val name: String? = null,
    val email: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** Non-null while asking "N changes aren't synced, sign out anyway?". */
    val confirmSignOut: Int? = null,
)

class SettingsViewModel(
    private val session: SessionManager,
    private val sync: SyncEngine,
    private val prefsStore: PrefsStore,
    private val syncController: SyncController,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    val prefs: StateFlow<AppPrefs> = prefsStore.prefs
    val syncing: StateFlow<Boolean> = syncController.syncing
    val syncMessage: StateFlow<String?> = syncController.message
    val lastSyncedAt: StateFlow<Long?> = syncController.lastSyncedAt

    fun updatePrefs(change: (AppPrefs) -> AppPrefs) {
        viewModelScope.launch { prefsStore.update(change) }
    }

    fun syncNow() = syncController.syncNow()

    fun load() {
        // This ViewModel outlives a sign-out, so start fresh each time Settings opens:
        // no old account's name/email, and no "busy" left over from the last sign-out.
        _state.value = SettingsUiState()
        viewModelScope.launch {
            when (val r = session.profile()) {
                is ApiResult.Ok -> _state.update {
                    it.copy(name = r.value.displayName.ifBlank { null }, email = r.value.email, error = null)
                }
                is ApiResult.Err -> _state.update { it.copy(error = describe(r.error)) }
            }
        }
    }

    fun requestSignOut() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            sync.sync() // give unsent changes one last chance to reach the server
            val unsynced = session.unsyncedCount()
            if (unsynced == 0) {
                session.logout()
            } else {
                _state.update { it.copy(busy = false, confirmSignOut = unsynced) }
            }
        }
    }

    fun confirmSignOut() {
        _state.update { it.copy(confirmSignOut = null, busy = true) }
        viewModelScope.launch { session.logout() }
    }

    fun cancelSignOut() = _state.update { it.copy(confirmSignOut = null) }

    fun deleteAccount() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val r = session.deleteAccount()) {
                is ApiResult.Ok -> {} // the app switches to the login screen by itself
                is ApiResult.Err -> _state.update { it.copy(busy = false, error = describe(r.error)) }
            }
        }
    }

    private fun describe(e: ApiError) = when (e) {
        is ApiError.Network -> "You're offline. Connect to the internet and try again."
        ApiError.SessionExpired -> "Please sign in again."
        is ApiError.Server -> "Server error (${e.status}). Try again later."
    }
}
