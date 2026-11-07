package dev.sushnt.journal.data

import dev.sushnt.journal.api.ApiResult
import dev.sushnt.journal.api.JournalApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Signing in and out, and deciding which screen the app shows.
 */
class SessionManager(
    private val api: JournalApi,
    private val tokens: SqliteTokenStore,
    private val store: EntryStore,
    private val sync: SyncEngine,
) {
    // Becomes true only after sign-in has fully finished (including the
    // "is this the same account as before?" check), so the list never flashes
    // another person's entries.
    private val ready = MutableStateFlow(tokens.signedIn.value)

    /** true: show the journal. false: show the login screen. */
    val signedIn: Flow<Boolean> = combine(ready, tokens.signedIn) { r, t -> r && t }

    suspend fun login(email: String, password: String): ApiResult<Unit> =
        api.login(email.trim(), password).also { if (it is ApiResult.Ok) afterSignIn() }

    suspend fun signup(email: String, password: String, displayName: String): ApiResult<Unit> =
        api.signup(email.trim(), password, displayName.trim()).also { if (it is ApiResult.Ok) afterSignIn() }

    /** Entries changed on this device that the server doesn't have yet. */
    suspend fun unsyncedCount(): Int = store.dirtyEntries().size

    /** Signs out and removes this account's entries from the device. */
    suspend fun logout() {
        ready.value = false
        api.logout()
        store.clear()
        tokens.setLastUserId(null)
    }

    private suspend fun afterSignIn() {
        val me = api.me()
        if (me is ApiResult.Ok) {
            // A different account than last time: drop the old account's local copy.
            if (tokens.lastUserId() != me.value.id) {
                store.clear()
                tokens.setLastUserId(me.value.id)
            }
        }
        ready.value = true
    }
}
