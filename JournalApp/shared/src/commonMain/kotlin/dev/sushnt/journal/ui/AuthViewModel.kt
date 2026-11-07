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

data class AuthUiState(
    val isSignup: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(private val session: SessionManager) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    fun toggleMode() = _state.update { it.copy(isSignup = !it.isSignup, error = null) }

    fun submit(email: String, password: String, displayName: String) {
        if (_state.value.loading) return

        // Check the obvious things here, so the user gets an answer without a network round trip.
        val problem = when {
            !email.trim().contains('@') -> "Enter a valid email address."
            _state.value.isSignup && password.length !in 8..72 -> "Password must be 8 to 72 characters."
            password.isEmpty() -> "Enter your password."
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = if (_state.value.isSignup) {
                session.signup(email, password, displayName)
            } else {
                session.login(email, password)
            }
            // On success the app switches to the journal by itself (SessionManager.signedIn).
            val error = (result as? ApiResult.Err)?.error?.let(::describe)
            _state.update { it.copy(loading = false, error = error) }
        }
    }

    private fun describe(error: ApiError): String = when (error) {
        is ApiError.Network -> "Can't reach the server. Check your internet connection."
        ApiError.SessionExpired -> "Please sign in again."
        is ApiError.Server -> when (error.status) {
            401 -> "Wrong email or password."
            409 -> "An account with this email already exists. Try signing in."
            429 -> "Too many attempts. Wait a minute and try again."
            400, 422 -> error.message.replaceFirstChar { it.uppercase() } + "."
            else -> "Something went wrong on the server (${error.status}). Try again."
        }
    }
}
