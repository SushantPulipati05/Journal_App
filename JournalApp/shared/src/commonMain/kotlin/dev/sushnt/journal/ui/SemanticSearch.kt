package dev.sushnt.journal.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sushnt.journal.data.Entry
import dev.sushnt.journal.data.AppPrefs

/*
 * "Similar in meaning": entries the server found by comparing embeddings, shown under
 * (phone) or above (desktop) the entries that contain the words you typed.
 */

/** The label and a short status line ("Searching…", "Still preparing 12 entries…"). */
@Composable
private fun MeaningHeader(state: MeaningSearch, modifier: Modifier = Modifier) {
    val note = when (state) {
        MeaningSearch.Searching -> "Searching…"
        is MeaningSearch.Unavailable -> state.reason
        is MeaningSearch.Results -> when {
            state.pending > 0 -> "Still preparing ${state.pending} ${if (state.pending == 1) "entry" else "entries"} for search…"
            state.hits.isEmpty() -> "Nothing else comes close."
            else -> null
        }
        MeaningSearch.Idle -> null
    }
    Column(modifier) {
        SectionLabel("Similar in meaning")
        if (note != null) Text(note, color = JournalColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** One result: when it was written, the title, and the paragraph that matched. */
@Composable
private fun MeaningCard(hit: MeaningHit, prefs: AppPrefs, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(source)
            .card()
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(longDateLabel(hit.entry.createdAt), color = JournalColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            hit.entry.title.ifBlank { "Untitled" },
            color = JournalColors.TextStrong,
            fontFamily = writingFont(prefs.serifFont),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hit.snippet.isNotBlank()) {
            Text(
                "“${hit.snippet}”",
                color = JournalColors.Preview,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Phone: a vertical list, below the word matches. Nothing while there's no query. */
@Composable
fun MeaningResultsColumn(state: MeaningSearch, prefs: AppPrefs, onOpen: (Entry) -> Unit, modifier: Modifier = Modifier) {
    if (state == MeaningSearch.Idle) return
    Column(modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MeaningHeader(state, Modifier.padding(start = 4.dp))
        if (state is MeaningSearch.Results) {
            state.hits.forEach { hit -> MeaningCard(hit, prefs, { onOpen(hit.entry) }, Modifier.fillMaxWidth()) }
        }
    }
}

/** Desktop: one row of cards above the gallery/timeline. */
@Composable
fun MeaningResultsRow(state: MeaningSearch, prefs: AppPrefs, onOpen: (Entry) -> Unit, modifier: Modifier = Modifier) {
    if (state == MeaningSearch.Idle) return
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MeaningHeader(state)
            Spacer(Modifier.width(8.dp))
        }
        if (state is MeaningSearch.Results && state.hits.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.hits, key = { it.entry.id }) { hit ->
                    MeaningCard(hit, prefs, { onOpen(hit.entry) }, Modifier.width(280.dp))
                }
            }
        }
    }
}
