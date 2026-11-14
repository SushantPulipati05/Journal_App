package dev.sushnt.journal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sushnt.journal.data.TrashedEntry
import dev.sushnt.journal.data.withoutPhotos
import org.koin.compose.viewmodel.koinViewModel

/** How long deleted entries stay in the Trash (must match EntryStore). */
private const val TRASH_DAYS = 30

/**
 * Deleted entries, kept for 30 days on this device. Restore puts one back in Journals
 * (with its date and photos); "Delete forever" removes it now.
 */
@Composable
fun TrashScreen(onBack: () -> Unit, vm: EntriesViewModel = koinViewModel()) {
    val trash by vm.trash.collectAsState()
    var confirmEmpty by remember { mutableStateOf(false) }
    PlatformBackHandler(enabled = true, onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                LineIcon(Glyph.Back, JournalColors.Text)
                Spacer(Modifier.width(4.dp))
                Text("Settings", color = JournalColors.Text, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(trash.isNotEmpty()) {
                TextButton(onClick = { confirmEmpty = true }) { Text("Empty trash", color = JournalColors.Danger, fontWeight = FontWeight.SemiBold) }
            }
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Trash", color = JournalColors.TextStrong, fontFamily = writingFont(true), fontSize = 32.sp, fontWeight = FontWeight.Medium)
            Text(
                "Deleted entries stay here for $TRASH_DAYS days, then they're gone for good.",
                color = JournalColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }

        if (trash.isEmpty()) {
            Text(
                "Nothing in the trash.",
                color = JournalColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(trash, key = { it.id }) { item ->
                    TrashCard(item, onRestore = { vm.restore(item) }, onDelete = { vm.deleteForever(item) }, modifier = Modifier.animateItem())
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty the trash?") },
            text = { Text("${trash.size} ${if (trash.size == 1) "entry" else "entries"} will be deleted for good. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    vm.emptyTrash()
                }) { Text("Delete forever", color = JournalColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrashCard(item: TrashedEntry, onRestore: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val daysLeft = (TRASH_DAYS - ((nowMillis() - item.deletedAt) / (24L * 60 * 60 * 1000)).toInt()).coerceAtLeast(0)
    val preview = remember(item.body) { item.body.withoutPhotos().trim() }
    Column(modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${dayHeader(localDate(item.createdAt))} · deleted ${relativeLabel(item.deletedAt)}",
            color = JournalColors.Muted,
            fontSize = 12.sp,
        )
        Text(
            item.title.ifBlank { "Untitled" },
            color = JournalColors.TextStrong,
            fontFamily = writingFont(true),
            fontSize = 19.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (preview.isNotBlank()) {
            Text(preview, color = JournalColors.Preview, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (daysLeft == 1) "1 day left" else "$daysLeft days left",
                color = if (daysLeft <= 3) JournalColors.Warning else JournalColors.Muted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            SmallAction("Delete forever", JournalColors.Danger, onDelete)
            Spacer(Modifier.width(8.dp))
            SmallAction("Restore", JournalColors.Accent, onRestore, filled = true)
        }
    }
}

@Composable
private fun SmallAction(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit, filled: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .then(if (filled) Modifier.background(JournalColors.AccentContainer) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
