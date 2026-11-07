package dev.sushnt.journal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.sushnt.journal.data.SessionManager
import dev.sushnt.journal.ui.AuthScreen
import dev.sushnt.journal.ui.EditorScreen
import dev.sushnt.journal.ui.EntriesScreen
import dev.sushnt.journal.ui.EntriesViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private const val NEW_ENTRY = "__new__"

/** The whole app. Every platform shows this. initKoin() must have run first. */
@Composable
fun App() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            val session = koinInject<SessionManager>()
            val signedIn by session.signedIn.collectAsState(initial = null)
            when (signedIn) {
                true -> Journal()
                false -> AuthScreen()
                null -> {} // first instant while reading the saved login
            }
        }
    }
}

@Composable
private fun Journal(vm: EntriesViewModel = koinViewModel()) {
    // Which entry is open: null = the list, NEW_ENTRY = a new one, else an entry id.
    // rememberSaveable keeps it when an Android phone rotates.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val entries by vm.entries.collectAsState()

    LaunchedEffect(Unit) { vm.syncNow() } // sync each time the journal appears

    val id = openId
    if (id == null) {
        EntriesScreen(
            vm = vm,
            onOpen = { openId = it.id },
            onNew = { openId = NEW_ENTRY },
        )
        return
    }

    val entry = if (id == NEW_ENTRY) null else entries.find { it.id == id }
    if (id != NEW_ENTRY && entry == null) {
        // The entry vanished (e.g. deleted on another device during a sync): back to the list.
        LaunchedEffect(id) { openId = null }
        return
    }
    EditorScreen(
        entry = entry,
        onSave = { title, body -> vm.save(entry?.id, title, body) },
        onDelete = { entry?.let { vm.delete(it.id) } },
        onClose = { openId = null },
    )
}
