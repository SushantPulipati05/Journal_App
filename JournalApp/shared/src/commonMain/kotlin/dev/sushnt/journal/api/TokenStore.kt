package dev.sushnt.journal.api

data class Tokens(val accessToken: String, val refreshToken: String)

/**
 * Where the login tokens are kept. An interface, so each platform can later
 * provide safe storage, and tests can use the in-memory version.
 */
interface TokenStore {
    suspend fun load(): Tokens?
    suspend fun save(tokens: Tokens)
    suspend fun clear()
}

/** Forgets everything when the app closes. Good for tests and for the first version. */
class InMemoryTokenStore(private var tokens: Tokens? = null) : TokenStore {
    override suspend fun load(): Tokens? = tokens
    override suspend fun save(tokens: Tokens) { this.tokens = tokens }
    override suspend fun clear() { tokens = null }
}
