package dev.sushnt.journal

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
private data class HealthResponse(val status: String)

/** Asks the server's /healthz endpoint and returns a short message for the screen. */
suspend fun checkServer(client: HttpClient): String =
    try {
        val health = client.get("${ApiConfig.BASE_URL}/healthz").body<HealthResponse>()
        "Server says: ${health.status}"
    } catch (e: CancellationException) {
        throw e // never swallow cancellation, or screens can't be closed cleanly
    } catch (e: Exception) {
        "Could not reach the server: ${e.message}"
    }
