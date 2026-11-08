@file:OptIn(ExperimentalMaterial3Api::class)

package dev.sushnt.journal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sushnt.journal.data.AppPrefs
import dev.sushnt.journal.data.Entry

/** The Journals tab: everything you've written, newest first, grouped by day. */
@Composable
fun JournalsScreen(
    vm: EntriesViewModel,
    prefs: AppPrefs,
    onOpen: (Entry) -> Unit,
    onSettings: () -> Unit,
    /** true on phones; false as the list pane beside the sidebar. */
    compact: Boolean = true,
    /** The entry open on the right (wide layout), highlighted in the list. */
    selectedId: String? = null,
    /** Goes up by one each time Ctrl+F asks to jump into the search box. */
    focusSearchSignal: Int = 0,
    /** Owned by the caller so the scroll position survives opening an entry. */
    listState: LazyListState = rememberLazyListState(),
    /** Space kept free at the bottom (e.g. for a floating tab bar); null = the default. */
    bottomPadding: Dp? = null,
) {
    val all by vm.entries.collectAsState()
    val sections by vm.sections.collectAsState()
    val query by vm.query.collectAsState()
    val streak by vm.streak.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val message by vm.message.collectAsState()
    val pendingDelete by vm.pendingDelete.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(focusSearchSignal) {
        if (focusSearchSignal > 0) runCatching { searchFocus.requestFocus() }
    }

    // "Entry deleted · Undo" after every swipe.
    LaunchedEffect(pendingDelete?.id) {
        if (pendingDelete == null) return@LaunchedEffect
        val result = snackbar.showSnackbar("Entry deleted", actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = if (compact) 12.dp else 32.dp, bottom = bottomPadding ?: if (compact) 120.dp else 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text("Journals", color = JournalColors.TextStrong, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (all.size == 1) "1 entry" else "${all.size} entries",
                            color = JournalColors.Muted,
                            fontSize = 13.sp,
                        )
                    }
                    if (compact) SettingsButton(onSettings)
                }
            }

            if (syncing) item(key = "syncing") { LinearProgressIndicator(Modifier.animateItem().fillMaxWidth().clip(CircleShape)) }
            message?.let { note ->
                item(key = "message") {
                    Text(note, color = JournalColors.Muted, fontSize = 12.sp, modifier = Modifier.animateItem().padding(horizontal = 4.dp))
                }
            }

            if (all.isNotEmpty()) {
                item(key = "search") { SearchField(query, vm::setQuery, searchFocus) }
            }
            if (prefs.showStreak && streak > 0 && query.isBlank()) {
                item(key = "streak") { Box(Modifier.animateItem()) { StreakCard(streak) } }
            }

            if (sections.isEmpty()) {
                item {
                    Text(
                        if (all.isEmpty()) "Nothing here yet.\nWhat you write on the Write tab shows up here."
                        else "No entries match \"$query\".",
                        color = JournalColors.Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
            }

            sections.forEach { section ->
                item(key = "header-${section.header}") {
                    SectionLabel(section.header, Modifier.animateItem().padding(top = 10.dp))
                }
                items(section.entries, key = { it.id }) { entry ->
                    // animateItem(): cards fade in, fade out, and glide into place when the list changes.
                    SwipeToDelete(Modifier.animateItem(), onDelete = { vm.deleteWithUndo(entry) }) {
                        EntryCard(entry, prefs, selected = entry.id == selectedId, onClick = { onOpen(entry) })
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = if (compact || bottomPadding != null) 92.dp else 24.dp)) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = JournalColors.Raised,
                contentColor = JournalColors.Text,
                actionColor = JournalColors.Accent,
            )
        }
    }
}

/** Swipe a card to the left to delete it. A red "Delete" shows underneath while dragging. */
@Composable
private fun SwipeToDelete(modifier: Modifier, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(CardShape).background(Color(0xFF3A1622)).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("Delete", color = JournalColors.Danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        },
    ) { content() }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, focus: FocusRequester) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(JournalColors.Card)
            .border(1.dp, JournalColors.BorderStrong, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(Glyph.Search, JournalColors.Muted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search your entries", color = PlaceholderColor, fontSize = 15.sp)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = JournalColors.Text, fontSize = 15.sp),
                cursorBrush = SolidColor(JournalColors.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search your entries" },
            )
        }
        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = fadeIn(Motion.quick()) + scaleIn(Motion.quick(), initialScale = 0.8f),
            exit = fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = 0.8f),
        ) {
            Text(
                "Clear",
                color = JournalColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onChange("") }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
    }
}

private val PlaceholderColor = Color(0xFF7C859B)

@Composable
private fun StreakCard(days: Int) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JournalColors.StreakCard)
            .border(1.dp, JournalColors.StreakBorder, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(JournalColors.AccentContainer),
            contentAlignment = Alignment.Center,
        ) { LineIcon(Glyph.Moon, JournalColors.Accent, size = 16.dp, strokeWidth = 2f) }
        Spacer(Modifier.width(10.dp))
        Text(
            if (days == 1) "1 day so far. Write again tomorrow to start a streak."
            else "$days days in a row. Keep it going.",
            color = JournalColors.Preview,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun EntryCard(entry: Entry, prefs: AppPrefs, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale(source)
            .then(
                if (selected) {
                    Modifier.clip(CardShape).background(Color(0xFF171B33)).border(1.dp, JournalColors.Accent, CardShape)
                } else {
                    Modifier.card()
                },
            )
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(timeLabel(entry.createdAt), color = JournalColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (entry.dirty) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(JournalColors.Warning))
                Spacer(Modifier.width(6.dp))
                Text("Not synced yet", color = JournalColors.Muted, fontSize = 12.sp)
            }
        }
        Text(
            entry.title.ifBlank { "Untitled" },
            color = JournalColors.TextStrong,
            fontFamily = writingFont(prefs.serifFont),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.body.isNotBlank()) {
            Text(
                entry.body.trim(),
                color = JournalColors.Preview,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
