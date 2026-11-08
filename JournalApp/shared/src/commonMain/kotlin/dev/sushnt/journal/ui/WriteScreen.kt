package dev.sushnt.journal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sushnt.journal.data.AppPrefs

/**
 * The Write tab: a blank page with the cursor already blinking.
 * No title field: the first line becomes the title (shown a little larger).
 */
@Composable
fun WriteScreen(
    vm: WriteViewModel,
    prefs: AppPrefs,
    keyboardOpen: Boolean,
    onSettings: () -> Unit,
    /** true on phones (settings button here, room for the tab bar); false next to the sidebar. */
    compact: Boolean = true,
) {
    val text by vm.text.collectAsState()
    val entryId by vm.entryId.collectAsState()
    val startedAt by vm.startedAt.collectAsState()
    val status by vm.status.collectAsState()
    val words = remember(text) { wordCount(text) }

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Opening the app (or this tab) drops you straight into typing.
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    val bodySize = prefs.textSize.sp
    val titleLook = remember(bodySize) { FirstLineAsTitle((bodySize * 1.4f).sp) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(longDateLabel(startedAt), color = JournalColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (text.isNotEmpty()) {
                SaveIndicator(status)
                Spacer(Modifier.width(12.dp))
            }
            if (compact && !keyboardOpen) SettingsButton(onSettings)
        }

        BasicTextField(
            value = text,
            onValueChange = vm::onTextChange,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .focusRequester(focus)
                .semantics { contentDescription = "Journal entry. The first line is the title." },
            textStyle = TextStyle(
                color = JournalColors.Body,
                fontFamily = writingFont(prefs.serifFont),
                fontSize = bodySize.sp,
                lineHeight = (bodySize * 1.6f).sp,
            ),
            cursorBrush = SolidColor(JournalColors.Accent),
            visualTransformation = titleLook,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, bottom = when {
                    keyboardOpen -> 4.dp
                    compact -> 96.dp // room for the tab bar
                    else -> 24.dp
                }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (entryId == null) "NEW ENTRY" else "EDITING",
                color = JournalColors.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Text("· ${timeLabel(startedAt)}", color = JournalColors.Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(if (words == 1) "1 word" else "$words words", color = JournalColors.Muted, fontSize = 12.sp)
            if (keyboardOpen) {
                TextButton(onClick = { focusManager.clearFocus(); keyboard?.hide() }) {
                    Text("Done", fontWeight = FontWeight.SemiBold)
                }
            } else if (text.isNotBlank()) {
                TextButton(onClick = {
                    vm.newEntry()
                    focus.requestFocus()
                    keyboard?.show()
                }) { Text("New entry", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun SaveIndicator(status: SaveStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            SaveStatus.Saved -> {
                LineIcon(Glyph.Check, JournalColors.Success, size = 14.dp, strokeWidth = 2.2f)
                Spacer(Modifier.width(6.dp))
                Text("Saved", color = JournalColors.Muted, fontSize = 12.sp)
            }
            SaveStatus.Saving -> Text("Saving…", color = JournalColors.Muted, fontSize = 12.sp)
            SaveStatus.Idle -> {}
        }
    }
}

/** Shows the first line bigger and bolder, like a title. Doesn't change the text itself. */
internal class FirstLineAsTitle(private val titleSize: TextUnit) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val end = text.text.indexOf('\n').let { if (it < 0) text.length else it }
        val styled = buildAnnotatedString {
            append(text)
            if (end > 0) {
                addStyle(SpanStyle(fontSize = titleSize, fontWeight = FontWeight.Medium, color = JournalColors.TextStrong), 0, end)
                // The first paragraph is exactly the title line, so it can get its own line height.
                addStyle(ParagraphStyle(lineHeight = titleSize * 1.25f), 0, end)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is FirstLineAsTitle && other.titleSize == titleSize
    override fun hashCode() = titleSize.hashCode()
}
