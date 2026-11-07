package dev.sushnt.journal.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = "https://server.test"

private fun MockRequestHandleScope.jsonResponse(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

/** A 401 the way our Go server sends it for a bad access token: with the Bearer challenge. */
private fun MockRequestHandleScope.bearerChallenge(): HttpResponseData =
    respond(
        """{"error":"invalid or expired token"}""",
        HttpStatusCode.Unauthorized,
        headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            HttpHeaders.WWWAuthenticate to listOf("Bearer"),
        ),
    )

private fun session(access: String, refresh: String) =
    """{"access_token":"$access","refresh_token":"$refresh","token_type":"Bearer","expires_at":"2026-11-07T10:00:00Z"}"""

private const val USER_JSON =
    """{"id":"u1","email":"a@b.c","display_name":"Ann","created_at":"2026-11-07T10:00:00Z"}"""

private fun HttpRequestData.bodyText(): String = (body as TextContent).text

class JournalApiTest {

    @Test
    fun loginSavesTokensAndSendsCredentials() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            jsonResponse(session("A1", "R1"))
        }
        val store = InMemoryTokenStore()
        val api = JournalApi(BASE, store, engine)

        val result = api.login("a@b.c", "secret-password")

        assertIs<ApiResult.Ok<Unit>>(result)
        assertEquals(Tokens("A1", "R1"), store.load())
        assertEquals("/auth/login", seen.single().url.encodedPath)
        assertTrue(seen.single().bodyText().contains(""""email":"a@b.c""""))
    }

    @Test
    fun wrongPasswordReturnsServerErrorAndSavesNothing() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            jsonResponse("""{"error":"invalid email or password"}""", HttpStatusCode.Unauthorized)
        }
        val store = InMemoryTokenStore(Tokens("OLD_A", "OLD_R"))
        val api = JournalApi(BASE, store, engine)

        val result = api.login("a@b.c", "wrong")

        assertIs<ApiResult.Err>(result)
        assertEquals(ApiError.Server(401, "invalid email or password"), result.error)
        assertEquals(1, calls, "a failed login must not trigger a token refresh")
        assertEquals(Tokens("OLD_A", "OLD_R"), store.load(), "old session must be left alone")
    }

    @Test
    fun duplicateSignupIsReportedAsServerError() = runTest {
        val engine = MockEngine {
            jsonResponse("""{"error":"email already registered"}""", HttpStatusCode.Conflict)
        }
        val api = JournalApi(BASE, InMemoryTokenStore(), engine)

        val result = api.signup("a@b.c", "secret-password", "Ann")

        assertIs<ApiResult.Err>(result)
        assertEquals(ApiError.Server(409, "email already registered"), result.error)
    }

    @Test
    fun signedInCallsCarryTheAccessToken() = runTest {
        var authHeader: String? = null
        val engine = MockEngine { request ->
            authHeader = request.headers[HttpHeaders.Authorization]
            jsonResponse(USER_JSON)
        }
        val api = JournalApi(BASE, InMemoryTokenStore(Tokens("A1", "R1")), engine)

        val result = api.me()

        assertIs<ApiResult.Ok<UserDto>>(result)
        assertEquals("Ann", result.value.displayName)
        assertEquals("Bearer A1", authHeader)
    }

    @Test
    fun expiredAccessTokenIsRefreshedAndTheCallRepeated() = runTest {
        var refreshCalls = 0
        var meCalls = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" -> {
                    refreshCalls++
                    jsonResponse(session("NEW_A", "NEW_R"))
                }
                "/me" -> {
                    meCalls++
                    if (request.headers[HttpHeaders.Authorization] == "Bearer NEW_A") {
                        jsonResponse(USER_JSON)
                    } else {
                        bearerChallenge()
                    }
                }
                else -> error("unexpected request ${request.url}")
            }
        }
        val store = InMemoryTokenStore(Tokens("OLD_A", "OLD_R"))
        val api = JournalApi(BASE, store, engine)

        val result = api.me()

        assertIs<ApiResult.Ok<UserDto>>(result)
        assertEquals(1, refreshCalls)
        assertEquals(2, meCalls)
        assertEquals(Tokens("NEW_A", "NEW_R"), store.load(), "the new pair must be saved")
    }

    @Test
    fun rejectedRefreshTokenMeansSessionExpiredAndSignOut() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" ->
                    jsonResponse("""{"error":"invalid refresh token"}""", HttpStatusCode.Unauthorized)
                else -> bearerChallenge()
            }
        }
        val store = InMemoryTokenStore(Tokens("OLD_A", "OLD_R"))
        val api = JournalApi(BASE, store, engine)

        val result = api.me()

        assertIs<ApiResult.Err>(result)
        assertEquals(ApiError.SessionExpired, result.error)
        assertNull(store.load(), "tokens must be cleared")
        assertTrue(!api.isSignedIn())
    }

    @Test
    fun serverTroubleDuringRefreshDoesNotLogTheUserOut() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" -> jsonResponse("""{"error":"internal server error"}""", HttpStatusCode.InternalServerError)
                else -> bearerChallenge()
            }
        }
        val store = InMemoryTokenStore(Tokens("OLD_A", "OLD_R"))
        val api = JournalApi(BASE, store, engine)

        val result = api.me()

        assertIs<ApiResult.Err>(result)
        assertIs<ApiError.Network>(result.error)
        assertNotNull(store.load(), "a server hiccup must not sign the user out")
    }

    @Test
    fun connectionFailureIsANetworkError() = runTest {
        val engine = MockEngine { throw RuntimeException("no route to host") }
        val api = JournalApi(BASE, InMemoryTokenStore(Tokens("A1", "R1")), engine)

        val result = api.me()

        assertIs<ApiResult.Err>(result)
        assertEquals(ApiError.Network("no route to host"), result.error)
    }

    @Test
    fun logoutClearsTokensEvenWhenOffline() = runTest {
        val engine = MockEngine { throw RuntimeException("offline") }
        val store = InMemoryTokenStore(Tokens("A1", "R1"))
        val api = JournalApi(BASE, store, engine)

        api.logout()

        assertNull(store.load())
    }

    @Test
    fun logoutTellsTheServerWhichSessionToRevoke() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond("", HttpStatusCode.NoContent)
        }
        val api = JournalApi(BASE, InMemoryTokenStore(Tokens("A1", "R1")), engine)

        api.logout()

        assertEquals("/auth/logout", seen.single().url.encodedPath)
        assertTrue(seen.single().bodyText().contains(""""refresh_token":"R1""""))
    }

    @Test
    fun pullSendsCursorAndParsesChanges() = runTest {
        var since: String? = null
        val engine = MockEngine { request ->
            since = request.url.parameters["since"]
            jsonResponse(
                """{"changes":[{"id":"e1","title":"Hi","body":"there","created_at":"2026-11-07T09:00:00Z",""" +
                    """"updated_at":"2026-11-07T09:00:00Z","deleted_at":null,"version":7}],""" +
                    """"next_cursor":7,"has_more":false}""",
            )
        }
        val api = JournalApi(BASE, InMemoryTokenStore(Tokens("A1", "R1")), engine)

        val result = api.pull(since = 5)

        assertIs<ApiResult.Ok<PullResponse>>(result)
        assertEquals("5", since)
        assertEquals(7L, result.value.nextCursor)
        assertEquals(false, result.value.hasMore)
        assertEquals("Hi", result.value.changes.single().title)
        assertEquals(7L, result.value.changes.single().version)
        assertNull(result.value.changes.single().deletedAt)
    }

    @Test
    fun pushSendsChangesAndParsesResults() = runTest {
        var sent = ""
        val engine = MockEngine { request ->
            sent = request.bodyText()
            jsonResponse("""{"results":[{"id":"e1","status":"applied","version":8},{"id":"e2","status":"stale"}]}""")
        }
        val api = JournalApi(BASE, InMemoryTokenStore(Tokens("A1", "R1")), engine)
        val change = SyncChangeDto("e1", "Hi", "there", "2026-11-07T09:00:00Z", "2026-11-07T09:00:00Z")

        val result = api.push(listOf(change))

        assertIs<ApiResult.Ok<PushResponse>>(result)
        assertTrue(sent.contains(""""created_at":"2026-11-07T09:00:00Z""""))
        assertEquals(listOf("applied", "stale"), result.value.results.map { it.status })
        assertEquals(8L, result.value.results.first().version)
    }

    @Test
    fun deletingTheAccountSignsOutLocally() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NoContent) }
        val store = InMemoryTokenStore(Tokens("A1", "R1"))
        val api = JournalApi(BASE, store, engine)

        val result = api.deleteAccount()

        assertIs<ApiResult.Ok<Unit>>(result)
        assertNull(store.load())
    }

    @Test
    fun switchingAccountsNeverSendsThePreviousUsersToken() = runTest {
        val seenAuth = mutableListOf<String?>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/login" ->
                    if (request.bodyText().contains("a@x.y")) jsonResponse(session("A_TOKEN", "A_R"))
                    else jsonResponse(session("B_TOKEN", "B_R"))
                "/auth/logout" -> respond("", HttpStatusCode.NoContent)
                else -> {
                    seenAuth += request.headers[HttpHeaders.Authorization]
                    jsonResponse(USER_JSON)
                }
            }
        }
        val api = JournalApi(BASE, InMemoryTokenStore(), engine)

        api.login("a@x.y", "password-a")
        api.me()
        api.logout()
        api.login("b@x.y", "password-b")
        api.me()

        assertEquals(listOf<String?>("Bearer A_TOKEN", "Bearer B_TOKEN"), seenAuth)
    }
}
