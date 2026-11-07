@file:OptIn(ExperimentalMaterial3Api::class)

package dev.sushnt.journal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(onClose: () -> Unit, vm: SettingsViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    var askDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }
    PlatformBackHandler(enabled = true, onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .widthIn(max = 520.dp),
        ) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(state.name ?: "—", style = MaterialTheme.typography.bodyLarge)
            Text(
                state.email ?: "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = vm::requestSignOut,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign out") }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Danger zone", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Deleting your account permanently removes it and every entry, on all your devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { askDelete = true },
                enabled = !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete my account") }
        }
    }

    state.confirmSignOut?.let { count ->
        AlertDialog(
            onDismissRequest = vm::cancelSignOut,
            title = { Text("Sign out?") },
            text = { Text("$count change(s) haven't reached the server yet and will be lost if you sign out now.") },
            confirmButton = { TextButton(onClick = vm::confirmSignOut) { Text("Sign out anyway") } },
            dismissButton = { TextButton(onClick = vm::cancelSignOut) { Text("Cancel") } },
        )
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete your account?") },
            text = { Text("This can't be undone. Your account and all your entries will be erased.") },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    vm.deleteAccount()
                }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
    }
}
