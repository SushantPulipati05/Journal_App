@file:OptIn(ExperimentalTime::class)

package dev.sushnt.journal.data

import dev.sushnt.journal.api.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * One place that runs syncs for every screen. Ask as often as you like with
 * [syncNow]: requests that arrive while a sync is running are merged into ONE
 * more sync afterwards, so nothing is missed and nothing runs twice at once.
 */
class SyncController(
    private val sync: SyncEngine,
    private val backgroundSync: BackgroundSync,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Channel<Unit>(Channel.CONFLATED)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    /** A short note for the user, e.g. "offline". null = all good. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** When the last successful sync finished (epoch ms), or null. */
    private val _lastSyncedAt = MutableStateFlow<Long?>(null)
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt

    init {
        scope.launch {
            for (request in requests) {
                _syncing.value = true
                runOnce()
                _syncing.value = false
            }
        }
    }

    fun syncNow() {
        requests.trySend(Unit)
    }

    private suspend fun runOnce() {
        _message.value = when (val result = sync.sync()) {
            is SyncResult.Done -> {
                _lastSyncedAt.value = Clock.System.now().toEpochMilliseconds()
                if (result.failed > 0) "${result.failed} change(s) were refused by the server." else null
            }
            is SyncResult.Failed -> when (val e = result.error) {
                is ApiError.Network -> {
                    // The OS retries as soon as the internet is back, even if the app is closed.
                    backgroundSync.syncSoon()
                    "Offline. Your writing is saved on this device and will sync later."
                }
                ApiError.SessionExpired -> null // the app switches to the login screen by itself
                is ApiError.Server -> "Sync failed (server error ${e.status})."
            }
        }
    }
}
