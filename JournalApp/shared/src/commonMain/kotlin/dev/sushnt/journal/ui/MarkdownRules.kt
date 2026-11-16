package dev.sushnt.journal.ui

/*
 * Formatting is stored as plain Markdown inside the entry text:
 *
 *   # Heading        ## Smaller heading
 *   - bullet         1. numbered
 *   - [ ] to do      - [x] done
 *   **bold**         *italic*
 *
 * So sync, merging, encryption and search keep working on plain text. This file is the
 * pure text logic (no Compose): recognising lines, the "type to format" rules, the
 * toggles used by the selection menu, "/" commands and keyboard shortcuts, and the
 * styling ranges the editor draws. The first line of an entry is its title and is never
 * formatted.
 */

/** What a line is, by the characters it starts with. */
internal enum class LineKind { Plain, Heading1, Heading2, Bullet, Numbered, Todo, Done }

/** The kind of a line and how many characters its marker ("- [ ] ", "# "...) takes. */
internal data class LinePrefix(val kind: LineKind, val length: Int, val number: Int = 0)

private val NumberedPrefix = Regex("""^(\d{1,3})\. """)

internal fun linePrefix(line: String): LinePrefix = when {
    line.startsWith("## ") -> LinePrefix(LineKind.Heading2, 3)
    line.startsWith("# ") -> LinePrefix(LineKind.Heading1, 2)
    line.startsWith("- [ ] ") || line.startsWith("* [ ] ") -> LinePrefix(LineKind.Todo, 6)
    line.startsWith("- [x] ") || line.startsWith("- [X] ") || line.startsWith("* [x] ") -> LinePrefix(LineKind.Done, 6)
    line.startsWith("- ") || line.startsWith("* ") -> LinePrefix(LineKind.Bullet, 2)
    else -> NumberedPrefix.find(line)?.let { LinePrefix(LineKind.Numbered, it.value.length, it.groupValues[1].toInt()) }
        ?: LinePrefix(LineKind.Plain, 0)
}

/** The marker a new line of [kind] starts with. */
internal fun markerFor(kind: LineKind, number: Int = 1): String = when (kind) {
    LineKind.Plain -> ""
    LineKind.Heading1 -> "# "
    LineKind.Heading2 -> "## "
    LineKind.Bullet -> "- "
    LineKind.Numbered -> "$number. "
    LineKind.Todo -> "- [ ] "
    LineKind.Done -> "- [x] "
}

/** Text after an edit, with the new selection. */
internal data class TextEdit(val text: String, val selStart: Int, val selEnd: Int = selStart)

internal fun lineStart(text: String, pos: Int): Int = if (pos <= 0) 0 else text.lastIndexOf('\n', pos - 1) + 1

internal fun lineEnd(text: String, pos: Int): Int = text.indexOf('\n', pos).let { if (it < 0) text.length else it }

private fun isTitleLine(text: String, pos: Int, firstLineIsTitle: Boolean) = firstLineIsTitle && lineStart(text, pos) == 0

// ---------------------------------------------------------------- A: type to format

/**
 * Runs after every keystroke. Recognises one typed character and:
 *  - Enter on a list line starts the next item ("- ", "- [ ] ", "3. "); Enter on an empty
 *    item ends the list instead;
 *  - "[] " or "[ ] " at the start of a line becomes a checkbox "- [ ] ".
 * Returns null when nothing needs changing.
 */
internal fun applyInputRules(old: String, new: String, newCursor: Int, firstLineIsTitle: Boolean): TextEdit? {
    if (new.length != old.length + 1 || newCursor < 1 || newCursor > new.length) return null
    if (new.substring(0, newCursor - 1) + new.substring(newCursor) != old) return null
    val typed = new[newCursor - 1]

    if (typed == '\n') {
        val start = lineStart(new, newCursor - 1)
        if (isTitleLine(new, start, firstLineIsTitle) && start == 0) return null
        val previous = new.substring(start, newCursor - 1)
        val prefix = linePrefix(previous)
        val isList = prefix.kind == LineKind.Bullet || prefix.kind == LineKind.Numbered ||
            prefix.kind == LineKind.Todo || prefix.kind == LineKind.Done
        if (!isList) return null
        if (previous.length == prefix.length) {
            // Enter on an empty item: the list ends, the line becomes ordinary text.
            return TextEdit(new.substring(0, start) + new.substring(newCursor), start)
        }
        val marker = when (prefix.kind) {
            LineKind.Bullet -> previous.substring(0, 2) // keep "-" or "*"
            LineKind.Numbered -> markerFor(LineKind.Numbered, prefix.number + 1)
            else -> markerFor(LineKind.Todo) // a new item under a done one starts unchecked
        }
        return TextEdit(new.substring(0, newCursor) + marker + new.substring(newCursor), newCursor + marker.length)
    }

    if (typed == ' ') {
        val start = lineStart(new, newCursor)
        if (isTitleLine(new, start, firstLineIsTitle)) return null
        val typedSoFar = new.substring(start, newCursor)
        if (typedSoFar == "[] " || typedSoFar == "[ ] ") {
            val marker = markerFor(LineKind.Todo)
            return TextEdit(new.substring(0, start) + marker + new.substring(newCursor), start + marker.length)
        }
    }
    return null
}

// ---------------------------------------------------------------- B, D: toggles

/** Bold/italic: wraps the selection in [mark] ("**" or "*"), or unwraps it if it's already wrapped. */
internal fun toggleWrap(text: String, selStart: Int, selEnd: Int, mark: String): TextEdit {
    var start = minOf(selStart, selEnd)
    var end = maxOf(selStart, selEnd)
    // Leave spaces outside: "**word **" isn't bold in Markdown.
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    if (start == end) {
        // Nothing selected: put an empty pair at the cursor and type inside it.
        return TextEdit(text.substring(0, start) + mark + mark + text.substring(start), start + mark.length)
    }
    val m = mark.length
    val wrappedOutside = start >= m && end + m <= text.length &&
        text.substring(start - m, start) == mark && text.substring(end, end + m) == mark &&
        !(mark == "*" && (text.getOrNull(start - 2) == '*' || text.getOrNull(end + 1) == '*'))
    if (wrappedOutside) {
        return TextEdit(text.substring(0, start - m) + text.substring(start, end) + text.substring(end + m), start - m, end - m)
    }
    val inner = text.substring(start, end)
    if (inner.length >= 2 * m && inner.startsWith(mark) && inner.endsWith(mark)) {
        return TextEdit(text.substring(0, start) + inner.substring(m, inner.length - m) + text.substring(end), start, end - 2 * m)
    }
    return TextEdit(text.substring(0, start) + mark + inner + mark + text.substring(end), start + m, end + m)
}

/**
 * Turns every line touched by the selection into [kind] (heading, bullet, checkbox...).
 * If they all are that already, they go back to plain text. The title line is skipped.
 */
internal fun setLineKind(text: String, selStart: Int, selEnd: Int, kind: LineKind, firstLineIsTitle: Boolean): TextEdit {
    val from = lineStart(text, minOf(selStart, selEnd))
    val to = lineEnd(text, maxOf(selStart, selEnd))
    val lines = text.substring(from, to).split('\n')
    var lineFrom = from
    val editable = lines.map { line ->
        val starts = lineFrom
        lineFrom += line.length + 1
        !(firstLineIsTitle && starts == 0)
    }
    val sameKind: (LineKind) -> Boolean = { k -> k == kind || (kind == LineKind.Todo && k == LineKind.Done) }
    val allAlready = lines.indices.filter { editable[it] }.all { sameKind(linePrefix(lines[it]).kind) }
    val target = if (allAlready) LineKind.Plain else kind

    var number = 0
    var shiftEnd = 0
    val firstLineDelta = IntArray(1)
    val rebuilt = lines.mapIndexed { i, line ->
        if (!editable[i]) return@mapIndexed line
        val prefix = linePrefix(line)
        val body = line.substring(prefix.length)
        if (target == LineKind.Numbered) number++
        val marker = markerFor(target, number)
        val delta = marker.length - prefix.length
        if (i == 0) firstLineDelta[0] = delta
        shiftEnd += delta
        marker + body
    }
    val shiftStart = firstLineDelta[0]
    val newText = text.substring(0, from) + rebuilt.joinToString("\n") + text.substring(to)
    val a = minOf(selStart, selEnd)
    val b = maxOf(selStart, selEnd)
    val newA = (a + shiftStart).coerceIn(from, newText.length)
    val newB = (b + shiftEnd).coerceIn(newA, newText.length)
    return TextEdit(newText, newA, newB)
}

/** Ticks or unticks the checkbox on the line containing [pos]. null if it isn't a checkbox line. */
internal fun toggleCheckbox(text: String, pos: Int): TextEdit? {
    val start = lineStart(text, pos)
    val prefix = linePrefix(text.substring(start, lineEnd(text, pos)))
    val mark = when (prefix.kind) {
        LineKind.Todo -> 'x'
        LineKind.Done -> ' '
        else -> return null
    }
    val newText = text.substring(0, start + 3) + mark + text.substring(start + 4)
    return TextEdit(newText, pos)
}

// ---------------------------------------------------------------- C: "/" commands

internal data class SlashCommand(val label: String, val hint: String, val kind: LineKind)

internal val SlashCommands = listOf(
    SlashCommand("Heading", "#", LineKind.Heading1),
    SlashCommand("Subheading", "##", LineKind.Heading2),
    SlashCommand("Bullet list", "-", LineKind.Bullet),
    SlashCommand("Numbered list", "1.", LineKind.Numbered),
    SlashCommand("Checklist", "[ ]", LineKind.Todo),
)

/**
 * When the line with the cursor is "/" plus a few letters ("/che"), the menu is open:
 * returns the matching commands. Otherwise null.
 */
internal fun slashMatches(text: String, cursor: Int, firstLineIsTitle: Boolean): List<SlashCommand>? {
    if (cursor < 1 || cursor > text.length) return null
    val start = lineStart(text, cursor)
    if (isTitleLine(text, start, firstLineIsTitle)) return null
    if (lineEnd(text, cursor) != cursor) return null
    val typed = text.substring(start, cursor)
    if (!typed.startsWith("/") || typed.length > 16 || typed.contains(' ')) return null
    val query = typed.drop(1).lowercase()
    return SlashCommands.filter { c -> query.isEmpty() || query in c.label.lowercase() }
        .takeIf { it.isNotEmpty() }
}

/** Replaces the "/query" line with the marker of [command]. */
internal fun applySlash(text: String, cursor: Int, command: SlashCommand): TextEdit {
    val start = lineStart(text, cursor)
    val marker = markerFor(command.kind)
    return TextEdit(text.substring(0, start) + marker + text.substring(cursor), start + marker.length)
}

/**
 * Enter pressed while the "/" menu was open: [new] is [old] plus a line break after
 * "/query". Picks the first match instead of breaking the line.
 */
internal fun slashOnEnter(old: String, new: String, newCursor: Int, firstLineIsTitle: Boolean): TextEdit? {
    if (new.length != old.length + 1 || newCursor < 1 || new[newCursor - 1] != '\n') return null
    if (new.substring(0, newCursor - 1) + new.substring(newCursor) != old) return null
    val matches = slashMatches(old, newCursor - 1, firstLineIsTitle) ?: return null
    return applySlash(old, newCursor - 1, matches.first())
}

// ---------------------------------------------------------------- drawing

/** How a range of the text looks. */
internal enum class MdStyle { Bold, Italic, Heading1, Heading2, Marker, Done, Bullet }

internal data class MdSpan(val start: Int, val end: Int, val style: MdStyle)

/**
 * How Markdown text looks in the editor. Same length as the text, so the cursor maps 1:1:
 * markers are swapped for symbols of the same length ("- " → "• ", "- [ ] " → "☐" and
 * zero-width spaces), or hidden as zero-width spaces ("**", "# ").
 */
internal class MdLook(val text: String, val spans: List<MdSpan>, val hidden: BooleanArray)

private const val ZW = '​'
private val BoldRegex = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*""")
private val ItalicRegex = Regex("""(?<![*\w])\*(?=[^\s*])([^*\n]+?)(?<=[^\s*])\*(?![*\w])""")

internal fun markdownLook(text: String, firstLineIsTitle: Boolean): MdLook {
    val chars = text.toCharArray()
    val hidden = BooleanArray(text.length)
    val spans = mutableListOf<MdSpan>()
    fun hide(from: Int, to: Int) {
        for (i in from until to) {
            chars[i] = ZW
            hidden[i] = true
        }
    }

    var start = 0
    while (start <= text.length) {
        val end = lineEnd(text, start)
        val line = text.substring(start, end)
        if (!(firstLineIsTitle && start == 0)) {
            val prefix = linePrefix(line)
            when (prefix.kind) {
                LineKind.Heading1, LineKind.Heading2 -> {
                    hide(start, start + prefix.length)
                    spans += MdSpan(start + prefix.length, end, if (prefix.kind == LineKind.Heading1) MdStyle.Heading1 else MdStyle.Heading2)
                }
                LineKind.Bullet -> {
                    chars[start] = '•'
                    spans += MdSpan(start, start + 1, MdStyle.Bullet)
                }
                LineKind.Todo, LineKind.Done -> {
                    chars[start] = if (prefix.kind == LineKind.Done) '☑' else '☐'
                    hide(start + 1, start + 5)
                    spans += MdSpan(start, start + 1, MdStyle.Bullet)
                    if (prefix.kind == LineKind.Done) spans += MdSpan(start + prefix.length, end, MdStyle.Done)
                }
                LineKind.Numbered -> spans += MdSpan(start, start + prefix.length - 1, MdStyle.Bullet)
                LineKind.Plain -> {}
            }
            // Bold and italic inside the line.
            for (m in BoldRegex.findAll(line)) {
                val s = start + m.range.first
                val e = start + m.range.last + 1
                hide(s, s + 2)
                hide(e - 2, e)
                spans += MdSpan(s + 2, e - 2, MdStyle.Bold)
            }
            for (m in ItalicRegex.findAll(line)) {
                val s = start + m.range.first
                val e = start + m.range.last + 1
                if (hidden[s] || hidden[e - 1]) continue // part of a bold marker
                hide(s, s + 1)
                hide(e - 1, e)
                spans += MdSpan(s + 1, e - 1, MdStyle.Italic)
            }
        }
        if (end >= text.length) break
        start = end + 1
    }
    return MdLook(chars.concatToString(), spans, hidden)
}

/** For reading (not editing): the same look, with the hidden characters really removed. */
internal fun markdownForReading(text: String, firstLineIsTitle: Boolean = false): MdLook {
    val look = markdownLook(text, firstLineIsTitle)
    val newIndex = IntArray(text.length + 1)
    val sb = StringBuilder()
    for (i in text.indices) {
        newIndex[i] = sb.length
        if (!look.hidden[i]) sb.append(look.text[i])
    }
    newIndex[text.length] = sb.length
    val spans = look.spans.map { MdSpan(newIndex[it.start], newIndex[it.end], it.style) }.filter { it.end > it.start }
    return MdLook(sb.toString(), spans, BooleanArray(sb.length))
}

/** Plain text without Markdown markers, for previews and word counts. */
fun String.withoutMarkdown(): String {
    if (none { it == '*' || it == '#' || it == '-' || it == '[' }) return this
    return markdownForReading(this).text.replace('•', ' ').replace("☐", "").replace("☑", "").lines()
        .joinToString("\n") { it.trimStart() }
}
