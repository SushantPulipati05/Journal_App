package dev.sushnt.journal.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import dev.sushnt.journal.api.TokenStore
import dev.sushnt.journal.api.Tokens
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the login on disk (a small SQLite file in the app's private folder), so the
 * user stays signed in after closing the app.
 *
 * Later improvement: Android Keystore / iOS Keychain for extra protection.
 */
class SqliteTokenStore(
    private val connection: SQLiteConnection,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TokenStore {
    private val lock = Mutex()
    private val _signedIn: MutableStateFlow<Boolean>

    /** True while tokens are saved. Turns false by itself when the server ends the session. */
    val signedIn: StateFlow<Boolean> get() = _signedIn

    init {
        connection.exec("CREATE TABLE IF NOT EXISTS kv (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
        _signedIn = MutableStateFlow(read(REFRESH) != null)
    }

    override suspend fun load(): Tokens? = locked {
        val access = read(ACCESS)
        val refresh = read(REFRESH)
        if (access != null && refresh != null) Tokens(access, refresh) else null
    }

    override suspend fun save(tokens: Tokens) = locked {
        write(ACCESS, tokens.accessToken)
        write(REFRESH, tokens.refreshToken)
        _signedIn.value = true
    }

    override suspend fun clear() = locked {
        write(ACCESS, null)
        write(REFRESH, null)
        _signedIn.value = false
    }

    /** Which account the entries on this device belong to. */
    suspend fun lastUserId(): String? = locked { read(LAST_USER) }

    suspend fun setLastUserId(id: String?) = locked { write(LAST_USER, id) }

    private suspend fun <T> locked(block: () -> T): T =
        lock.withLock { withContext(dispatcher) { block() } }

    private fun read(key: String): String? =
        connection.queryRows("SELECT value FROM kv WHERE key = ?", { it.bindText(1, key) }) { it.getText(0) }
            .firstOrNull()

    private fun write(key: String, value: String?) {
        if (value == null) {
            connection.exec("DELETE FROM kv WHERE key = ?") { it.bindText(1, key) }
        } else {
            connection.exec("INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)") {
                it.bindText(1, key)
                it.bindText(2, value)
            }
        }
    }

    companion object {
        private const val ACCESS = "access_token"
        private const val REFRESH = "refresh_token"
        private const val LAST_USER = "last_user_id"

        fun open(driver: SQLiteDriver, path: String) = SqliteTokenStore(driver.open(path))
    }
}
