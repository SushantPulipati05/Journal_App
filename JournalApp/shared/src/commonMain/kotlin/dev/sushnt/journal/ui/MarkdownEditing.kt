package dev.sushnt.journal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/*
 * The Compose half of formatting (the text rules are in MarkdownRules.kt):
 *  - MarkdownLook draws headings, bullets, checkboxes, bold and italic while you type;
 *  - SelectionPill: select text → a small B / I / H / • / ☐ menu under it;
 *  - SlashMenu: type "/" on an empty line → Heading, Bullet list, Checklist…;
 *  - formattingShortcut: Ctrl+B, Ctrl+I, Ctrl+1/2/0, Ctrl+Shift+7/8/9, Ctrl+Enter.
 */

private fun TextUnit.orDefault(): TextUnit = if (isUnspecified) 17.sp else this

internal fun mdSpanStyle(style: MdStyle, bodySize: TextUnit): SpanStyle = when (style) {
    MdStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold, color = JournalColors.TextStrong)
    MdStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
    MdStyle.Heading1 -> SpanStyle(fontSize = bodySize.orDefault() * 1.45f, fontWeight = FontWeight.SemiBold, color = JournalColors.TextStrong)
    MdStyle.Heading2 -> SpanStyle(fontSize = bodySize.orDefault() * 1.2f, fontWeight = FontWeight.SemiBold, color = JournalColors.TextStrong)
    MdStyle.Marker -> SpanStyle(color = JournalColors.Muted)
    MdStyle.Done -> SpanStyle(color = JournalColors.Muted, textDecoration = TextDecoration.LineThrough)
    MdStyle.Bullet -> SpanStyle(color = JournalColors.Accent, fontWeight = FontWeight.SemiBold)
}

/**
 * How the editor draws an entry: the title line (when [titleSize] is set) and the
 * Markdown below it. The drawn text has exactly the same length as the real text, so
 * the cursor lines up character for character (OffsetMapping.Identity).
 */
internal class MarkdownLook(
    private val titleSize: TextUnit?,
    private val bodySize: TextUnit,
    /** Only here so a theme change makes a different look (and the text is redrawn). */
    private val dark: Boolean = JournalColors.isDark,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val look = markdownLook(text.text, firstLineIsTitle = titleSize != null)
        val styled = buildAnnotatedString {
            append(look.text)
            if (titleSize != null) {
                val end = text.text.indexOf('\n').let { if (it < 0) text.length else it }
                if (end > 0) {
                    addStyle(SpanStyle(fontSize = titleSize, fontWeight = FontWeight.Medium, color = JournalColors.TextStrong), 0, end)
                    addStyle(ParagraphStyle(lineHeight = titleSize * 1.25f), 0, end)
                }
            }
            for (span in look.spans) addStyle(mdSpanStyle(span.style, bodySize), span.start, span.end)
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is MarkdownLook && other.titleSize == titleSize && other.bodySize == bodySize && other.dark == dark
    override fun hashCode() = (titleSize.hashCode() * 31 + bodySize.hashCode()) * 31 + dark.hashCode()
}

/** Formatted text for reading (timeline, entry view): markers removed, styles applied. */
fun markdownAnnotated(text: String, bodySize: TextUnit): AnnotatedString {
    val look = markdownForReading(text)
    return buildAnnotatedString {
        append(look.text)
        for (span in look.spans) addStyle(mdSpanStyle(span.style, bodySize), span.start, span.end)
    }
}

/** Keyboard shortcuts on computers. Returns the edit, or null if [event] isn't one. */
internal fun formattingShortcut(event: KeyEvent, text: String, selStart: Int, selEnd: Int, firstLineIsTitle: Boolean): TextEdit? {
    if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed)) return null
    val shift = event.isShiftPressed
    return when {
        event.key == Key.B && !shift -> toggleWrap(text, selStart, selEnd, "**")
        event.key == Key.I && !shift -> toggleWrap(text, selStart, selEnd, "*")
        event.key == Key.One && !shift -> setLineKind(text, selStart, selEnd, LineKind.Heading1, firstLineIsTitle)
        event.key == Key.Two && !shift -> setLineKind(text, selStart, selEnd, LineKind.Heading2, firstLineIsTitle)
        event.key == Key.Zero && !shift -> setLineKind(text, selStart, selEnd, LineKind.Plain, firstLineIsTitle)
        event.key == Key.Seven && shift -> setLineKind(text, selStart, selEnd, LineKind.Numbered, firstLineIsTitle)
        event.key == Key.Eight && shift -> setLineKind(text, selStart, selEnd, LineKind.Bullet, firstLineIsTitle)
        event.key == Key.Nine && shift -> setLineKind(text, selStart, selEnd, LineKind.Todo, firstLineIsTitle)
        event.key == Key.Enter -> toggleCheckbox(text, selStart)
        else -> null
    }
}

/** Places a small floating menu just under [anchor] (a rectangle inside the text field). */
@Composable
private fun FloatingMenu(anchor: Rect, gap: Int, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val offset = with(density) { IntOffset(anchor.left.toInt().coerceAtLeast(0), (anchor.bottom + gap.dp.toPx()).toInt()) }
    // Not focusable: the keyboard stays up and the text keeps its selection.
    Popup(alignment = Alignment.TopStart, offset = offset, properties = PopupProperties(focusable = false)) {
        content()
    }
}

/**
 * Select some text and this pill appears under it. It sits below the selection because
 * the phone's own Copy / Paste menu takes the space above it.
 */
@Composable
internal fun SelectionPill(anchor: Rect, onBold: () -> Unit, onItalic: () -> Unit, onHeading: () -> Unit, onBullet: () -> Unit, onChecklist: () -> Unit) {
    FloatingMenu(anchor, gap = 30) {
        Row(
            Modifier
                .shadow(8.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(JournalColors.Raised)
                .border(1.dp, JournalColors.Border, RoundedCornerShape(50))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton("B", "Bold", onBold, SpanStyle(fontWeight = FontWeight.Bold))
            PillButton("I", "Italic", onItalic, SpanStyle(fontStyle = FontStyle.Italic))
            PillButton("H", "Heading", onHeading, SpanStyle(fontWeight = FontWeight.SemiBold))
            PillButton("•", "Bullet list", onBullet, SpanStyle(fontWeight = FontWeight.Bold))
            PillButton("☐", "Checklist", onChecklist, SpanStyle())
        }
    }
}

@Composable
private fun PillButton(label: String, description: String, onClick: () -> Unit, style: SpanStyle) {
    Text(
        AnnotatedString(label, style),
        color = JournalColors.TextStrong,
        fontSize = 16.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Type "/" on an empty line: this list appears under it. Tap one, or press Enter for the first. */
@Composable
internal fun SlashMenu(anchor: Rect, commands: List<SlashCommand>, onPick: (SlashCommand) -> Unit) {
    FloatingMenu(anchor, gap = 6) {
        Column(
            Modifier
                .widthIn(min = 200.dp, max = 260.dp)
                .shadow(10.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(JournalColors.Raised)
                .border(1.dp, JournalColors.Border, RoundedCornerShape(14.dp))
                .padding(vertical = 6.dp),
        ) {
            commands.forEachIndexed { i, command ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onPick(command) }
                        .background(if (i == 0) JournalColors.AccentContainer.copy(alpha = 0.5f) else JournalColors.Raised)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(command.label, color = JournalColors.TextStrong, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Text(command.hint, color = JournalColors.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}
