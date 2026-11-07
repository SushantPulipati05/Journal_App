package dev.sushnt.journal.ui

import androidx.compose.runtime.Composable

sealed interface GoogleTokenResult {
    data class Token(val idToken: String) : GoogleTokenResult
    data object Cancelled : GoogleTokenResult
    data class Error(val message: String) : GoogleTokenResult
}

/** Shows Google's account picker and returns an ID token for our server to check. */
fun interface GoogleSignIn {
    suspend fun requestIdToken(): GoogleTokenResult
}

/** null where Google sign-in isn't available yet (desktop, iOS) or not configured. */
@Composable
expect fun rememberGoogleSignIn(): GoogleSignIn?
