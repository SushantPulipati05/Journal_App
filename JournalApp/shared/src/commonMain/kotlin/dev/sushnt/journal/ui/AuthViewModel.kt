package dev.sushnt.journal.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sushnt.journal.api.ApiError
import dev.sushnt.journal.api.ApiResult
import dev.sushnt.journal.data.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where we are in the "forgot password" flow. */
enum class ResetStep { EnterEmail, EnterCode }

data class AuthUiState(
    val isSignup: Boolean = false,
    /** null = normal sign-in / sign-up screen. */
    val resetStep: ResetStep? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** A neutral note, e.g. "We sent a code to ...". */
    val info: String? = null,
)

class AuthViewModel(private val session: SessionManager) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    fun toggleMode() = _state.update { it.copy(isSignup = !it.isSignup, error = null, info = null) }

    fun submit(email: String, password: String, displayName: String) {
        if (_state.value.loading) return

        // Check the obvious things here, so the user gets an answer without a network round trip.
        val problem = when {
            !email.isEmailLike() -> "Enter a valid email address."
            _state.value.isSignup && password.length !in 8..72 -> "Password must be 8 to 72 characters."
            password.isEmpty() -> "Enter your password."
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }

        launchSignIn({ describe(it) }) {
            if (_state.value.isSignup) session.signup(email, password, displayName)
            else session.login(email, password)
        }
        // On success the app switches to the journal by itself (SessionManager.signedIn).
    }

    /** Called with the token from Google's account picker. */
    fun signInWithGoogle(idToken: String) = launchSignIn({ describe(it, google = true) }) {
        session.loginWithGoogle(idToken)
    }

    /** The picker itself failed (no account, no Play services...). */
    fun showError(message: String) = _state.update { it.copy(error = message) }

    // ---------- forgot password ----------

    fun startReset() = _state.update { it.copy(resetStep = ResetStep.EnterEmail, error = null, info = null) }

    fun cancelReset() = _state.update { it.copy(resetStep = null, error = null, info = null) }

    /** Step 1: email a code. Also used for "Send a new code". */
    fun sendResetCode(email: String) {
        if (_state.value.loading) return
        if (!email.isEmailLike()) {
            _state.update { it.copy(error = "Enter a valid email address.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, info = null) }
            when (val r = session.requestPasswordReset(email)) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        resetStep = ResetStep.EnterCode,
                        // The server never says whether the account exists, so neither do we.
                        info = "If an account exists for ${email.trim()}, we've emailed it a 6-digit code. " +
                            "It expires in 15 minutes. Check your spam folder too.",
                    )
                }
                is ApiResult.Err -> _state.update { it.copy(loading = false, error = describe(r.error)) }
            }
        }
    }

    /** Step 2: the code plus a new password. Signs in on success. */
    fun submitReset(email: String, code: String, newPassword: String) {
        if (_state.value.loading) return
        val problem = when {
            code.trim().length != 6 || !code.trim().all { it.isDigit() } -> "Enter the 6-digit code from the email."
            newPassword.length !in 8..72 -> "Password must be 8 to 72 characters."
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }
        launchSignIn({ describe(it, reset = true) }) { session.resetPassword(email, code, newPassword) }
    }

    // ---------- internals ----------

    /** Runs a sign-in style call with the loading spinner and error handling. */
    private fun launchSignIn(describeError: (ApiError) -> String, call: suspend () -> ApiResult<Unit>) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = call()
            val error = (result as? ApiResult.Err)?.error?.let(describeError)
            _state.update { it.copy(loading = false, error = error) }
        }
    }

    private fun String.isEmailLike(): Boolean {
        val e = trim()
        return e.contains('@') && e.substringAfter('@').contains('.')
    }

    private fun describe(error: ApiError, google: Boolean = false, reset: Boolean = false): String = when (error) {
        is ApiError.Network -> "Can't reach the server. Check your internet connection."
        ApiError.SessionExpired -> "Please sign in again."
        is ApiError.Server -> when {
            reset && error.status == 400 -> "That code is wrong or has expired. Check it, or send a new code."
            error.status == 401 -> if (google) "Google sign-in failed. Please try again." else "Wrong email or password."
            error.status == 409 && google -> error.message.replaceFirstChar { it.uppercase() } + "."
            error.status == 409 -> "An account with this email already exists. Try signing in."
            error.status == 429 -> "Too many attempts. Wait a minute and try again."
            error.status == 503 -> "This isn't available right now. Try again later."
            error.status == 400 || error.status == 422 -> error.message.replaceFirstChar { it.uppercase() } + "."
            else -> "Something went wrong on the server (${error.status}). Try again."
        }
    }
}
