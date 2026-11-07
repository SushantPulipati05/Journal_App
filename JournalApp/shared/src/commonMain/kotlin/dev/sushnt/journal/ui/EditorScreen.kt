@file:OptIn(ExperimentalMaterial3Api::class)

package dev.sushnt.journal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sushnt.journal.data.Entry

/**
 * Writing screen. Leaving it (Done, or the phone's back gesture) saves automatically,
 * the way Notes apps do.
 *
 * @param entry null for a brand-new entry.
 */
@Composable
fun EditorScreen(
    entry: Entry?,
    onSave: (title: String, body: String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var title by rememberSaveable(entry?.id) { mutableStateOf(entry?.title ?: "") }
    var body by rememberSaveable(entry?.id) { mutableStateOf(entry?.body ?: "") }
    var askDelete by rememberSaveable { mutableStateOf(false) }

    val saveAndClose = {
        onSave(title, body)
        onClose()
    }
    PlatformBackHandler(enabled = true, onBack = saveAndClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (entry == null) "New entry" else "Edit entry") },
                navigationIcon = { TextButton(onClick = saveAndClose) { Text("Done") } },
                actions = {
                    if (entry != null) {
                        TextButton(onClick = { askDelete = true }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(MAX_TITLE) },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("What's on your mind?") },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("It will be removed from all your devices.") },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    onDelete()
                    onClose()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
    }
}
