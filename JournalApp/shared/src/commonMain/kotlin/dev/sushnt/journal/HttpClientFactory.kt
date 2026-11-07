package dev.sushnt.journal

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** How JSON is read and written everywhere in the app. */
val apiJson = Json {
    ignoreUnknownKeys = true // the server may add fields later; old apps must not break
    explicitNulls = false
}

/**
 * Builds the HTTP client used everywhere in the app.
 *
 * We don't name an engine here. Each platform has exactly one on its classpath
 * (OkHttp on Android, CIO on desktop, Darwin on iOS) and Ktor picks it up
 * by itself, so this one function works on all three.
 */
fun createHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(apiJson)
    }
}
