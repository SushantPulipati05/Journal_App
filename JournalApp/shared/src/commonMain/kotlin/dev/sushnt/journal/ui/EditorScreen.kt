package dev.sushnt.journal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sushnt.journal.data.AppPrefs
import dev.sushnt.journal.data.Entry
import kotlinx.coroutines.delay

/**
 * One entry, opened from the Journals tab. It reads like a page, but it IS the
 * editor: tap anywhere in the text and the cursor appears. Changes save themselves.
 */
@Composable
fun EntryScreen(
    entry: Entry,
    prefs: AppPrefs,
    onBack: () -> Unit,
    onSaveText: (String) -> Unit,
    onDelete: () -> Unit,
    /** false when shown beside the list on a wide screen (nothing to go back to). */
    showBack: Boolean = true,
    /** Desktop entry sheet: newer/older arrows and a close button. null = not in a sheet. */
    sheet: SheetControls? = null,
) {
    PlatformBackHandler(enabled = showBack, onBack = onBack)

    var text by rememberSaveable(entry.id) { mutableStateOf(joinEntryText(entry.title, entry.body)) }
    val latestText by rememberUpdatedState(text)

    // Save a moment after typing stops, and once more when leaving the screen.
    LaunchedEffect(text) {
        delay(800)
        onSaveText(text)
    }
    DisposableEffect(entry.id) {
        onDispose { onSaveText(latestText) }
    }

    val words = remember(text) { wordCount(text) }
    val size = prefs.textSize.sp
    var addPhoto by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                TextButton(onClick = onBack) {
                    LineIcon(Glyph.Back, JournalColors.Text)
                    Spacer(Modifier.width(4.dp))
                    Text("Journals", color = JournalColors.Text, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.weight(1f))
            if (sheet != null) {
                IconAction(Glyph.Back, "Newer entry", JournalColors.Text, { sheet.onNewer?.invoke() }, enabled = sheet.onNewer != null)
                IconAction(Glyph.Forward, "Older entry", JournalColors.Text, { sheet.onOlder?.invoke() }, enabled = sheet.onOlder != null)
            }
            IconAction(Glyph.Image, "Add photos where the cursor is", JournalColors.Accent, { addPhoto++ })
            // Same as swiping the card away: it can be undone for a few seconds.
            IconAction(Glyph.Trash, "Delete entry", JournalColors.Danger, onDelete)
            if (sheet != null) IconAction(Glyph.Close, "Close (Esc)", JournalColors.Text, sheet.onClose)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 48.dp),
        ) {
            Text(
                "${longDateLabel(entry.createdAt)} · ${timeLabel(entry.createdAt)}".uppercase(),
                color = JournalColors.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${if (words == 1) "1 word" else "$words words"} · ${readMinutes(words)} min read",
                color = JournalColors.Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            // Tap anywhere to edit; photos sit between the paragraphs.
            EntryEditor(
                text = text,
                onTextChange = { text = it },
                textStyle = TextStyle(
                    color = JournalColors.Body,
                    fontFamily = writingFont(prefs.serifFont),
                    fontSize = size.sp,
                    lineHeight = (size * 1.65f).sp,
                ),
                titleSize = (size * 1.5f).sp,
                addPhotoSignal = addPhoto,
                minLastHeight = 320.dp, // so tapping below the last line still starts typing
            )
        }
    }
}

/** The buttons an entry gets when it's open as a sheet over the desktop Journals page. */
class SheetControls(
    val onNewer: (() -> Unit)?,
    val onOlder: (() -> Unit)?,
    val onClose: () -> Unit,
)
