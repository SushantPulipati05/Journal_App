package dev.sushnt.journal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AuthScreen(vm: AuthViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    // Shared by every step, so the email typed on the sign-in screen carries over.
    var email by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            when (val step = state.resetStep) {
                null -> SignInContent(vm, state, email, onEmailChange = { email = it })
                else -> ResetContent(vm, state, step, email, onEmailChange = { email = it })
            }
        }
    }
}

@Composable
private fun ColumnScope.SignInContent(
    vm: AuthViewModel,
    state: AuthUiState,
    email: String,
    onEmailChange: (String) -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val google = rememberGoogleSignIn()
    val scope = rememberCoroutineScope()
    // The running Google sign-in. On desktop it waits for the browser, so the
    // button turns into "Cancel" meanwhile.
    var googleJob by remember { mutableStateOf<Job?>(null) }

    Header(
        title = "Journal",
        subtitle = if (state.isSignup) "Create your account" else "Sign in to your journal",
    )

    if (state.isSignup) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }
    EmailField(email, onEmailChange)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        supportingText = { if (state.isSignup) Text("8 to 72 characters") },
        modifier = Modifier.fillMaxWidth(),
    )
    if (!state.isSignup) {
        TextButton(
            onClick = vm::startReset,
            enabled = !state.loading,
            modifier = Modifier.align(Alignment.End),
        ) { Text("Forgot password?") }
    }

    Messages(state)

    Spacer(Modifier.height(12.dp))
    PrimaryButton(
        text = if (state.isSignup) "Create account" else "Sign in",
        loading = state.loading,
        onClick = { vm.submit(email, password, name) },
    )

    if (google != null) {
        Spacer(Modifier.height(12.dp))
        Text(
            "or",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(12.dp))
        val waiting = googleJob != null
        OutlinedButton(
            onClick = {
                val running = googleJob
                if (running != null) {
                    running.cancel() // "Cancel" while waiting
                    googleJob = null
                } else {
                    googleJob = scope.launch {
                        try {
                            when (val r = google.requestIdToken()) {
                                is GoogleTokenResult.Token -> vm.signInWithGoogle(r.idToken)
                                is GoogleTokenResult.Error -> vm.showError(r.message)
                                GoogleTokenResult.Cancelled -> {} // user closed the picker
                            }
                        } finally {
                            // Only clear it if a newer attempt hasn't replaced us.
                            if (googleJob == coroutineContext[Job]) googleJob = null
                        }
                    }
                }
            },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(if (waiting) "Waiting for Google… (tap to cancel)" else "Continue with Google") }
    }

    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = vm::toggleMode,
        enabled = !state.loading,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        Text(if (state.isSignup) "Already have an account? Sign in" else "New here? Create an account")
    }
}

@Composable
private fun ColumnScope.ResetContent(
    vm: AuthViewModel,
    state: AuthUiState,
    step: ResetStep,
    email: String,
    onEmailChange: (String) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }

    PlatformBackHandler(enabled = true, onBack = vm::cancelReset)

    when (step) {
        ResetStep.EnterEmail -> {
            Header(
                title = "Reset password",
                subtitle = "Enter your account's email and we'll send you a 6-digit code.",
            )
            EmailField(email, onEmailChange)
            Messages(state)
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Send code", state.loading) { vm.sendResetCode(email) }
        }

        ResetStep.EnterCode -> {
            Header(title = "Check your email", subtitle = null)
            Messages(state)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { input -> code = input.filter { it.isDigit() }.take(6) },
                label = { Text("6-digit code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("New password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("8 to 72 characters. You'll be signed out on your other devices.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Set new password", state.loading) { vm.submitReset(email, code, newPassword) }
            TextButton(
                onClick = { vm.sendResetCode(email) },
                enabled = !state.loading,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Send a new code") }
        }
    }

    TextButton(
        onClick = vm::cancelReset,
        enabled = !state.loading,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) { Text("Back to sign in") }
}

// ---------- small shared pieces ----------

@Composable
private fun Header(title: String, subtitle: String?) {
    Text(title, style = MaterialTheme.typography.headlineLarge)
    if (subtitle != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun EmailField(email: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = email,
        onValueChange = onChange,
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Messages(state: AuthUiState) {
    state.info?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun PrimaryButton(text: String, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !loading,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(text)
    }
}
