package dev.sushnt.journal.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import dev.sushnt.journal.ApiConfig

@Composable
actual fun rememberGoogleSignIn(): GoogleSignIn? {
    if (ApiConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) return null
    // The picker is a system sheet on top of our Activity, so it needs the Activity's context.
    val context = LocalContext.current
    return remember(context) { AndroidGoogleSignIn(context) }
}

private class AndroidGoogleSignIn(private val context: Context) : GoogleSignIn {
    private val credentials = CredentialManager.create(context)

    override suspend fun requestIdToken(): GoogleTokenResult {
        // The "Sign in with Google" button flow: always shows every Google account on the phone.
        val option = GetSignInWithGoogleOption.Builder(ApiConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = credentials.getCredential(context, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleTokenResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleTokenResult.Error("Google sent an unexpected response.")
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleTokenResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleTokenResult.Error("No Google account on this device. Add one in Settings → Accounts.")
        } catch (e: GetCredentialException) {
            GoogleTokenResult.Error("Google sign-in failed: ${e.message ?: e.type}")
        } catch (e: GoogleIdTokenParsingException) {
            GoogleTokenResult.Error("Google sent an invalid response.")
        }
    }
}
