package dev.sushnt.journal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sun.net.httpserver.HttpServer
import dev.sushnt.journal.createHttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import kotlin.time.Duration.Companion.minutes

@Composable
actual fun rememberGoogleSignIn(): GoogleSignIn? = remember { DesktopGoogleSignIn.loadOrNull() }

/**
 * "Sign in with Google" for the desktop app, the way Google recommends for
 * installed apps (OAuth with a loopback redirect + PKCE):
 *
 *  1. Start a tiny web server on this computer (127.0.0.1, random port).
 *  2. Open the browser on Google's sign-in page.
 *  3. Google sends the browser back to our tiny server with a one-time code.
 *  4. Trade that code (plus the PKCE secret only we know) for an ID token.
 *  5. Hand the ID token to our Go server, exactly like Android does.
 */
internal class DesktopGoogleSignIn(
    private val clientId: String,
    private val clientSecret: String,
) : GoogleSignIn {

    override suspend fun requestIdToken(): GoogleTokenResult = withContext(Dispatchers.IO) { signIn() }

    private suspend fun signIn(): GoogleTokenResult {
        // PKCE: a random secret. Google only sees its hash now and the secret
        // itself later, so a stolen code is useless to anyone else.
        val verifier = randomUrlSafe(32)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(16) // proves the answer belongs to THIS sign-in

        val reply = CompletableDeferred<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery)
            val ok = params["code"] != null && params["state"] == state
            val page = (if (ok) PAGE_DONE else PAGE_FAILED).toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, page.size.toLong())
            exchange.responseBody.use { it.write(page) }
            // Ignore stray requests (e.g. /favicon.ico); finish only on Google's answer.
            if ("code" in params || "error" in params) reply.complete(params)
        }
        server.start()

        try {
            val redirectUri = "http://127.0.0.1:${server.address.port}"
            val url = "https://accounts.google.com/o/oauth2/v2/auth?" + listOf(
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "response_type" to "code",
                "scope" to "openid email profile",
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "state" to state,
                "prompt" to "select_account",
            ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }

            if (!openBrowser(url)) return GoogleTokenResult.Error("Couldn't open your web browser.")

            val params = withTimeoutOrNull(5.minutes) { reply.await() }
                ?: return GoogleTokenResult.Cancelled // the user never finished in the browser

            params["error"]?.let { error ->
                return if (error == "access_denied") GoogleTokenResult.Cancelled
                else GoogleTokenResult.Error("Google sign-in failed: $error")
            }
            if (params["state"] != state) return GoogleTokenResult.Error("Sign-in response didn't match. Try again.")
            val code = params["code"] ?: return GoogleTokenResult.Error("Google didn't send a sign-in code.")

            return exchangeCode(code, verifier, redirectUri)
        } finally {
            server.stop(0)
        }
    }

    /** Step 4: one-time code + PKCE secret → Google ID token. */
    private suspend fun exchangeCode(code: String, verifier: String, redirectUri: String): GoogleTokenResult {
        val client = createHttpClient()
        return try {
            val response = client.submitForm(
                url = "https://oauth2.googleapis.com/token",
                formParameters = parameters {
                    append("code", code)
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    append("redirect_uri", redirectUri)
                    append("grant_type", "authorization_code")
                    append("code_verifier", verifier)
                },
            )
            if (!response.status.isSuccess()) {
                return GoogleTokenResult.Error("Google refused the sign-in (HTTP ${response.status.value}).")
            }
            val idToken = response.body<GoogleTokenResponse>().idToken
            if (idToken.isNullOrBlank()) GoogleTokenResult.Error("Google didn't send an ID token.")
            else GoogleTokenResult.Token(idToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GoogleTokenResult.Error("Couldn't reach Google: ${e.message}")
        } finally {
            client.close()
        }
    }

    @Serializable
    private data class GoogleTokenResponse(@SerialName("id_token") val idToken: String? = null)

    companion object {
        /**
         * Reads the "Desktop app" OAuth client from
         * shared/src/jvmMain/resources/google-desktop.properties (kept out of git).
         * Missing file = no Google button on desktop.
         */
        fun loadOrNull(): DesktopGoogleSignIn? {
            val stream = DesktopGoogleSignIn::class.java.getResourceAsStream("/google-desktop.properties")
                ?: return null
            val props = Properties().apply { stream.use { load(it) } }
            val id = props.getProperty("clientId")?.trim().orEmpty()
            val secret = props.getProperty("clientSecret")?.trim().orEmpty()
            return if (id.isEmpty() || secret.isEmpty()) null else DesktopGoogleSignIn(id, secret)
        }

        private val random = SecureRandom()

        private fun randomUrlSafe(bytes: Int): String =
            base64Url(ByteArray(bytes).also { random.nextBytes(it) })

        private fun base64Url(data: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(data)

        private fun parseQuery(raw: String?): Map<String, String> =
            raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
                val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }

        private fun openBrowser(url: String): Boolean = try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                val os = System.getProperty("os.name").lowercase()
                val cmd = if (os.contains("mac")) listOf("open", url) else listOf("xdg-open", url)
                ProcessBuilder(cmd).start()
            }
            true
        } catch (e: Exception) {
            false
        }

        private fun page(title: String, text: String) = """
            <!doctype html><html><head><meta charset="utf-8"><title>Journal</title></head>
            <body style="font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;height:90vh;margin:0">
            <div style="text-align:center"><h2>$title</h2><p>$text</p></div></body></html>
        """.trimIndent()

        private val PAGE_DONE = page("You're signed in", "You can close this tab and go back to Journal.")
        private val PAGE_FAILED = page("Sign-in didn't finish", "Close this tab and try again in Journal.")
    }
}
