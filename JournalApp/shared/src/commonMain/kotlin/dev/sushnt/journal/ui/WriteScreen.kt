package dev.sushnt.journal.ui

import dev.sushnt.journal.data.SpeechModel

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * The Write tab: a calm page with the cursor already blinking.
 * No title field: the first line becomes the title (shown a little larger).
 */
@Composable
fun WriteScreen(
    vm: WriteViewModel,
    prefs: AppPrefs,
    keyboardOpen: Boolean,
    onSettings: () -> Unit,
    /** true on phones (settings button here); false next to the sidebar. */
    compact: Boolean = true,
    /** Leave room at the bottom for the floating Write/Journals pill. */
    tabBarVisible: Boolean = compact,
) {
    val text by vm.text.collectAsState()
    val entryId by vm.entryId.collectAsState()
    val startedAt by vm.startedAt.collectAsState()
    val status by vm.status.collectAsState()
    val words = remember(text) { wordCount(text) }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var addPhoto by remember { mutableStateOf(0) }
    // Dictation: spoken words go into the editor at the cursor.
    val editorHandle = remember { EditorHandle() }
    var dictationNote by remember { mutableStateOf<String?>(null) }
    val dictation = rememberDictation(
        language = prefs.dictationLanguage,
        model = SpeechModel.fromId(prefs.speechModel) ?: defaultSpeechModel,
        onText = { words, final -> editorHandle.dictate(words, final) },
        onStopped = { editorHandle.endDictation() },
        onError = { dictationNote = it },
    )
    DictationDialogs(dictation)
    LaunchedEffect(dictationNote) {
        if (dictationNote != null) {
            kotlinx.coroutines.delay(5_000)
            dictationNote = null
        }
    }
    // A new page gets a fresh editor (and the cursor back at the top).
    var page by remember { mutableStateOf(0) }

    // Opening the app (or this tab) drops you straight into typing.
    LaunchedEffect(Unit) { keyboard?.show() }

    val bottomGap by animateDpAsState(
        when {
            keyboardOpen -> 4.dp
            tabBarVisible -> 100.dp // room for the floating tab bar
            else -> 40.dp
        },
        Motion.standard(),
        label = "bottom gap",
    )

    val bodySize = prefs.textSize.sp
    val font = writingFont(prefs.serifFont)
    val writingStyle = TextStyle(
        color = JournalColors.Body,
        fontFamily = font,
        fontSize = bodySize.sp,
        lineHeight = (bodySize * 1.75f).sp,
    )

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = if (compact) 24.dp else 8.dp),
    ) {
        if (compact) {
            // ---- phone header: small weekday over the date; a ✓ when saved; your avatar.
            val day = localDate(startedAt)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        day.dayOfWeek.name,
                        color = JournalColors.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp,
                        maxLines = 1,
                    )
                    Text(
                        "${day.day} ${day.month.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        color = JournalColors.TextStrong,
                        fontFamily = writingFont(true),
                        fontSize = 22.sp,
                        maxLines = 1,
                    )
                }
                AnimatedVisibility(visible = text.isNotEmpty(), enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick())) {
                    SaveIndicator(status, iconOnly = true)
                }
                if (!keyboardOpen) {
                    Spacer(Modifier.width(10.dp))
                    SettingsButton(onSettings)
                }
            }
        } else {
            // ---- header: "• DAILY JOURNAL   Monday, 9 November" ...... "✓ Saved"
            Row(
                Modifier.fillMaxWidth().padding(top = if (compact) 12.dp else 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(JournalColors.Accent))
                Spacer(Modifier.width(8.dp))
                Text("DAILY JOURNAL", color = JournalColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, maxLines = 1)
                Spacer(Modifier.width(12.dp))
                Text(longDateLabel(startedAt), color = JournalColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(visible = text.isNotEmpty(), enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick())) {
                    SaveIndicator(status)
                }
                if (compact && !keyboardOpen) {
                    Spacer(Modifier.width(12.dp))
                    SettingsButton(onSettings)
                }
            }
        }

        // ---- the page: text with photos between the paragraphs
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = if (compact) 20.dp else 24.dp, bottom = 16.dp),
        ) {
            key(page, startedAt) { // a new entry (e.g. the + in the tab bar) gets a fresh, focused page
                EntryEditor(
                    text = text,
                    onTextChange = vm::onTextChange,
                    textStyle = writingStyle,
                    titleSize = (bodySize * 1.3f).sp,
                    placeholder = "What's on your mind today? Note down your thoughts, a plan for the day, or a quiet reflection…",
                    addPhotoSignal = addPhoto,
                    handle = editorHandle,
                    focusOnStart = true,
                    minLastHeight = 240.dp,
                    // Desktop: small photos so most of the window stays for writing.
                    photoMaxHeight = if (compact) 300.dp else 240.dp,
                    photoMaxWidth = if (compact) 480.dp else 420.dp,
                    refocusAfterPhoto = !compact,
                )
            }
        }

        if (compact) {
            // ---- phone footer: "9:06 PM · 28 words" and a photo button. New entry lives on the
            // + next to the tab bar. While typing this row sits right on top of the keyboard.
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = bottomGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val listening = dictation.listening || dictation.status != null
                Text(
                    when {
                        dictationNote != null -> dictationNote!!
                        dictation.status != null -> dictation.status!!
                        dictation.ready -> "Listening… tap the mic to stop"
                        listening -> "Getting ready…"
                        else -> "${timeLabel(startedAt)} · ${if (words == 1) "1 word" else "$words words"}"
                    },
                    color = if (listening) JournalColors.Accent else JournalColors.Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                MicButton(dictation)
                IconAction(Glyph.Image, "Add photo", Color(0xFFC9CEDB), { addPhoto++ })
                if (keyboardOpen) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { focusManager.clearFocus(); keyboard?.hide() }) {
                        Text("Done", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            // ---- footer: "• NEW ENTRY · 12:13 AM    6 WORDS    1 MIN READ ...... New entry"
            Box(Modifier.fillMaxWidth().height(1.dp).background(JournalColors.Border))
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = bottomGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(JournalColors.Accent))
                Spacer(Modifier.width(8.dp))
                FooterLabel(if (entryId == null) "New entry" else "Editing", JournalColors.Accent)
                FooterLabel(" · ${timeLabel(startedAt)}", JournalColors.Muted)
                Spacer(Modifier.width(18.dp))
                FooterLabel(if (words == 1) "1 word" else "$words words", JournalColors.Muted)
                if (!compact) {
                    Spacer(Modifier.width(18.dp))
                    FooterLabel("${readMinutes(words)} min read", JournalColors.Muted)
                }
                Spacer(Modifier.weight(1f))
                (dictationNote ?: dictation.status ?: if (dictation.ready) "Listening… click the mic to stop" else null)?.let {
                    FooterLabel(it, JournalColors.Accent)
                    Spacer(Modifier.width(8.dp))
                }
                MicButton(dictation)
                // Adds photos where the cursor is.
                TextButton(onClick = { addPhoto++ }) {
                    LineIcon(Glyph.Image, JournalColors.Accent)
                    if (!compact || !keyboardOpen) {
                        Spacer(Modifier.width(6.dp))
                        Text("Photo", fontWeight = FontWeight.SemiBold)
                    }
                }
                if (keyboardOpen) {
                    TextButton(onClick = { focusManager.clearFocus(); keyboard?.hide() }) {
                        Text("Done", fontWeight = FontWeight.SemiBold)
                    }
                } else if (text.isNotBlank()) {
                    TextButton(onClick = {
                        vm.newEntry()
                        page++ // fresh editor with the cursor on the empty page
                        keyboard?.show()
                    }) { Text("New entry", fontWeight = FontWeight.SemiBold) }
                } else {
                    Spacer(Modifier.height(48.dp)) // keeps the line the same height either way
                }
            }
        }
    }
}

@Composable
private fun FooterLabel(text: String, color: Color) {
    Text(text.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, maxLines = 1)
}

@Composable
private fun SaveIndicator(status: SaveStatus, iconOnly: Boolean = false) {
    AnimatedContent(
        targetState = status,
        transitionSpec = { fadeIn(Motion.standard()) togetherWith fadeOut(Motion.quick()) },
        label = "save status",
    ) { shown ->
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (shown) {
            SaveStatus.Saved -> {
                LineIcon(Glyph.Check, JournalColors.Success, size = if (iconOnly) 16.dp else 14.dp, strokeWidth = 2.2f)
                if (!iconOnly) {
                    Spacer(Modifier.width(6.dp))
                    Text("Saved", color = JournalColors.Muted, fontSize = 12.sp)
                }
            }
            SaveStatus.Saving -> Text("Saving…", color = JournalColors.Muted, fontSize = 12.sp)
            SaveStatus.Idle -> {}
        }
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
