package dev.sushnt.journal.api

import dev.sushnt.journal.apiJson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException

/**
 * Everything the app can ask the server. Screens never touch HTTP directly.
 *
 * Token handling is automatic: every signed-in call carries the access token,
 * and when the server says it has expired, the library swaps the refresh token
 * for a new pair and repeats the call, without the screen noticing.
 *
 * @param engine only tests pass one (a fake server). The app lets Ktor pick the platform's own.
 */
class JournalApi(
    private val baseUrl: String,
    private val tokenStore: TokenStore,
    engine: HttpClientEngine? = null,
) {
    private val host = Url(baseUrl).host

    // For signup, login, refresh and logout. It has no Auth plugin, so an old
    // token can never interfere with logging in.
    private val plain: HttpClient = newClient(engine) {}

    // For everything that needs a signed-in user.
    private val authed: HttpClient = newClient(engine) {
        install(Auth) {
            bearer {
                loadTokens {
                    tokenStore.load()?.let { BearerTokens(it.accessToken, it.refreshToken) }
                }
                refreshTokens {
                    val current = oldTokens?.refreshToken ?: return@refreshTokens null
                    refreshSession(current)?.let { BearerTokens(it.accessToken, it.refreshToken) }
                }
                // Send the token right away instead of waiting for a 401 first,
                // but only to our own server.
                sendWithoutRequest { it.url.host == host }
            }
        }
    }

    // ---------- accounts ----------

    suspend fun signup(email: String, password: String, displayName: String): ApiResult<Unit> =
        startSession {
            plain.post("$baseUrl/auth/signup") { jsonBody(SignupRequest(email, password, displayName)) }
        }

    suspend fun login(email: String, password: String): ApiResult<Unit> =
        startSession {
            plain.post("$baseUrl/auth/login") { jsonBody(LoginRequest(email, password)) }
        }

    /** Signs in (or creates an account) with an ID token from Google's account picker. */
    suspend fun googleSignIn(idToken: String): ApiResult<Unit> =
        startSession {
            plain.post("$baseUrl/auth/google") { jsonBody(GoogleSignInRequest(idToken)) }
        }

    /** Asks the server to email a 6-digit reset code. Succeeds even for unknown emails (on purpose). */
    suspend fun forgotPassword(email: String): ApiResult<Unit> =
        call(authed = false, request = {
            plain.post("$baseUrl/auth/password/forgot") { jsonBody(ForgotPasswordRequest(email)) }
        }) { }

    /** Sets a new password with the emailed code, and signs in. */
    suspend fun resetPassword(email: String, code: String, newPassword: String): ApiResult<Unit> =
        startSession {
            plain.post("$baseUrl/auth/password/reset") { jsonBody(ResetPasswordRequest(email, code, newPassword)) }
        }

    /** Signs out here no matter what, and tells the server on a best-effort basis. */
    suspend fun logout() {
        val tokens = tokenStore.load()
        forgetSession()
        if (tokens != null) {
            try {
                plain.post("$baseUrl/auth/logout") { jsonBody(RefreshRequest(tokens.refreshToken)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline? We're signed out on this device anyway.
            }
        }
    }

    suspend fun isSignedIn(): Boolean = tokenStore.load() != null

    suspend fun me(): ApiResult<UserDto> =
        call(authed = true, request = { authed.get("$baseUrl/me") }) { it.body<UserDto>() }

    /** Permanently deletes the account and all its entries on the server. */
    suspend fun deleteAccount(): ApiResult<Unit> {
        val result = call(authed = true, request = { authed.delete("$baseUrl/me") }) { }
        if (result is ApiResult.Ok) forgetSession()
        return result
    }

    // ---------- sync ----------

    suspend fun push(changes: List<SyncChangeDto>): ApiResult<PushResponse> =
        call(authed = true, request = {
            authed.post("$baseUrl/sync/push") { jsonBody(PushRequest(changes)) }
        }) { it.body<PushResponse>() }

    suspend fun pull(since: Long, limit: Int = 100): ApiResult<PullResponse> =
        call(authed = true, request = {
            authed.get("$baseUrl/sync/pull") {
                parameter("since", since)
                parameter("limit", limit)
            }
        }) { it.body<PullResponse>() }

    fun close() {
        plain.close()
        authed.close()
    }

    // ---------- internals ----------

    /**
     * The Auth plugin keeps the last access token in memory. After a sign-out or
     * a switch to another account it must forget it, or requests would still go
     * out as the PREVIOUS user (that was a real bug).
     */
    private fun dropCachedToken() {
        authed.authProvider<BearerAuthProvider>()?.clearToken()
    }

    private suspend fun forgetSession() {
        tokenStore.clear()
        dropCachedToken()
    }

    private suspend fun startSession(request: suspend () -> HttpResponse): ApiResult<Unit> =
        when (val result = call(authed = false, request = request) { it.body<SessionDto>() }) {
            is ApiResult.Ok -> {
                tokenStore.save(Tokens(result.value.accessToken, result.value.refreshToken))
                dropCachedToken() // never reuse the previous account's token
                ApiResult.Ok(Unit)
            }
            is ApiResult.Err -> result
        }

    /**
     * Trades a refresh token for a new pair. Returns null when the server
     * rejects the token (the user must sign in again). If the server or network
     * is merely unavailable it THROWS instead, so a bad connection never logs
     * anyone out.
     */
    private suspend fun refreshSession(refreshToken: String): SessionDto? {
        val response = plain.post("$baseUrl/auth/refresh") { jsonBody(RefreshRequest(refreshToken)) }
        return when {
            response.status.isSuccess() -> {
                val session = response.body<SessionDto>()
                tokenStore.save(Tokens(session.accessToken, session.refreshToken))
                session
            }
            response.status == HttpStatusCode.Unauthorized -> {
                tokenStore.clear()
                null
            }
            else -> throw IllegalStateException("refresh unavailable: HTTP ${response.status.value}")
        }
    }

    /** Runs one request and turns every outcome, success or failure, into an ApiResult. */
    private suspend fun <T> call(
        authed: Boolean,
        request: suspend () -> HttpResponse,
        parse: suspend (HttpResponse) -> T,
    ): ApiResult<T> {
        val response = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ApiResult.Err(ApiError.Network(e.message ?: "network error"))
        }

        if (response.status.isSuccess()) {
            return try {
                ApiResult.Ok(parse(response))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ApiResult.Err(ApiError.Server(response.status.value, "unexpected response from server"))
            }
        }

        if (authed && response.status == HttpStatusCode.Unauthorized) {
            forgetSession()
            return ApiResult.Err(ApiError.SessionExpired)
        }
        return ApiResult.Err(ApiError.Server(response.status.value, errorMessage(response)))
    }

    private suspend fun errorMessage(response: HttpResponse): String =
        try {
            response.body<ErrorDto>().error
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            response.status.description
        }

    private fun newClient(engine: HttpClientEngine?, extra: HttpClientConfig<*>.() -> Unit): HttpClient {
        val config: HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) { json(apiJson) }
            extra()
        }
        return if (engine != null) HttpClient(engine, config) else HttpClient(config)
    }

    private inline fun <reified T> HttpRequestBuilder.jsonBody(value: T) {
        contentType(ContentType.Application.Json)
        setBody(value)
    }
}
