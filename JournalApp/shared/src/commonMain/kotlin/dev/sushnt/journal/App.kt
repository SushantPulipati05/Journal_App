package dev.sushnt.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// TEMPORARY screen: proves the app can reach the server on every platform.
// The real screens (login, entries) replace it in the next steps.
@Composable
@Preview
fun App() {
    MaterialTheme {
        val client = remember { createHttpClient() }
        DisposableEffect(Unit) { onDispose { client.close() } }

        val scope = rememberCoroutineScope()
        var result by remember { mutableStateOf("Not checked yet") }
        var loading by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeContentPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Journal", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Button(
                enabled = !loading,
                onClick = {
                    scope.launch {
                        loading = true
                        result = checkServer(client)
                        loading = false
                    }
                },
            ) {
                Text(if (loading) "Checking..." else "Check server")
            }
            Spacer(Modifier.height(16.dp))
            Text(result)
        }
    }
}
