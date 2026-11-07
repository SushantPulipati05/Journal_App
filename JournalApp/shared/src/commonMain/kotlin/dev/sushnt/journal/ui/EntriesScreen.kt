@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)

package dev.sushnt.journal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sushnt.journal.data.Entry
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Composable
fun EntriesScreen(
    vm: EntriesViewModel,
    onOpen: (Entry) -> Unit,
    onNew: () -> Unit,
) {
    val entries by vm.entries.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val message by vm.message.collectAsState()
    val confirm by vm.confirmSignOut.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Journal") },
                actions = {
                    TextButton(onClick = vm::syncNow, enabled = !syncing) { Text("Sync") }
                    TextButton(onClick = vm::requestSignOut) { Text("Sign out") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew) { Text("New entry") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No entries yet.\nTap \"New entry\" to write your first one.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    // bottom padding keeps the last card clear of the button
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        EntryCard(entry, onClick = { onOpen(entry) })
                    }
                }
            }
        }
    }

    confirm?.let { count ->
        AlertDialog(
            onDismissRequest = vm::cancelSignOut,
            title = { Text("Sign out?") },
            text = {
                Text("$count change(s) haven't reached the server yet and will be lost if you sign out now.")
            },
            confirmButton = { TextButton(onClick = vm::confirmSignOut) { Text("Sign out anyway") } },
            dismissButton = { TextButton(onClick = vm::cancelSignOut) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EntryCard(entry: Entry, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                entry.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                formatDate(entry.createdAt) + if (entry.dirty) "  ·  not synced yet" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "2026-11-07" (UTC for now; local time zones come later). */
private fun formatDate(epochMillis: Long): String =
    Instant.fromEpochMilliseconds(epochMillis).toString().take(10)
