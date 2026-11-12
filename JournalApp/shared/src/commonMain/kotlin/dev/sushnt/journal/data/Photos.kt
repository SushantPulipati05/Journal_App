package dev.sushnt.journal.data

import androidx.compose.ui.graphics.ImageBitmap

/*
 * How photos live inside an entry.
 *
 * A photo is a line of its own in the entry's body: {{photo:<uuid>}}. So the text says
 * WHERE each photo sits (between which paragraphs), and it syncs with the entry like
 * any other text. The image file itself is stored separately (PhotoStore) and synced
 * through the server's /attachments endpoints (PhotoSync).
 */

private val MarkerRegex = Regex("""\{\{photo:([0-9a-fA-F-]{36})\}\}""")

/** The line that stands for a photo in the entry text. */
fun photoMarker(id: String) = "{{photo:$id}}"

/** Photo ids in the order they appear in [text]. */
fun photoIds(text: String): List<String> = MarkerRegex.findAll(text).map { it.groupValues[1] }.toList()

/** The text without photo lines, for previews, search and word counts. */
fun String.withoutPhotos(): String =
    if (!contains("{{photo:")) this
    else replace(Regex("""\n?\{\{photo:[0-9a-fA-F-]{36}\}\}\n?"""), "\n").trim('\n')

/** Pieces of an entry, in order: text, photo, text, photo, text... Always starts and ends with text. */
sealed interface EntryPart {
    data class Text(val text: String) : EntryPart
    data class Photo(val id: String) : EntryPart
}

/** Splits entry text at its photo lines. [joinParts] turns it back into exactly the same text. */
fun splitParts(text: String): List<EntryPart> {
    val parts = mutableListOf<EntryPart>()
    var rest = 0
    for (m in Regex("""\n?\{\{photo:([0-9a-fA-F-]{36})\}\}\n?""").findAll(text)) {
        parts += EntryPart.Text(text.substring(rest, m.range.first))
        parts += EntryPart.Photo(m.groupValues[1])
        rest = m.range.last + 1
    }
    parts += EntryPart.Text(text.substring(rest))
    return parts
}

/** The opposite of [splitParts]: each photo goes on its own line. */
fun joinParts(parts: List<EntryPart>): String = buildString {
    parts.forEachIndexed { i, part ->
        when (part) {
            is EntryPart.Text -> append(part.text)
            is EntryPart.Photo -> {
                append('\n')
                append(photoMarker(part.id))
                // The text after a photo always starts on a new line.
                if (i < parts.lastIndex) append('\n')
            }
        }
    }
}

/** A photo after shrinking: JPEG bytes, at most 1600 px on the long side, no EXIF (location etc.). */
class PreparedPhoto(val bytes: ByteArray, val width: Int, val height: Int)

/** Longest side of a stored photo. Big enough for a full-screen view, small enough to sync fast. */
const val PHOTO_MAX_SIDE = 1600

// ---- what each platform provides (Photos.jvm.kt / Photos.android.kt / Photos.ios.kt) ----

/** Decodes any common image, turns it upright, shrinks it and re-encodes it as JPEG. null = not an image. Slow: call off the UI thread. */
expect fun preparePhoto(bytes: ByteArray): PreparedPhoto?

/** Turns JPEG/PNG bytes into something Compose can draw. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

expect fun writeFileBytes(path: String, bytes: ByteArray)
expect fun readFileBytes(path: String): ByteArray?
expect fun deleteFileAt(path: String)
expect fun makeDirs(path: String)
