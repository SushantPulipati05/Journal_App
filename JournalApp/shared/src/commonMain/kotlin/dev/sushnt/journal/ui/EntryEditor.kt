package dev.sushnt.journal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sushnt.journal.data.EntryPart
import dev.sushnt.journal.data.Photo
import dev.sushnt.journal.data.PhotoStore
import dev.sushnt.journal.data.joinParts
import dev.sushnt.journal.data.photoIds
import dev.sushnt.journal.data.splitParts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** The server allows this many photos in one entry. */
const val MAX_PHOTOS_PER_ENTRY = 12

/** One piece of the page being edited. [key] stays the same while it's edited, so focus isn't lost. */
private sealed interface Block {
    val key: Long

    data class Text(override val key: Long, val value: TextFieldValue) : Block
    data class Photo(override val key: Long, val id: String) : Block
}

private var keyCounter = 0L
private fun nextKey() = ++keyCounter

private fun List<Block>.toText(): String = joinParts(map {
    when (it) {
        is Block.Text -> EntryPart.Text(it.value.text)
        is Block.Photo -> EntryPart.Photo(it.id)
    }
})

/**
 * Builds blocks for [text]. If the shape is the same as [previous] (same photos in the
 * same places), the old keys and cursor positions are kept, so typing isn't disturbed.
 */
private fun blocksFor(text: String, previous: List<Block>?, cursorAtEnd: Boolean = false): List<Block> {
    val parts = splitParts(text)
    val sameShape = previous != null && previous.size == parts.size &&
        previous.zip(parts).all { (b, p) -> (b is Block.Text && p is EntryPart.Text) || (b is Block.Photo && p is EntryPart.Photo && b.id == p.id) }
    return parts.mapIndexed { i, part ->
        when (part) {
            is EntryPart.Photo -> Block.Photo(if (sameShape) previous!![i].key else nextKey(), part.id)
            is EntryPart.Text -> {
                val old = if (sameShape) previous!![i] as Block.Text else null
                val selection = when {
                    old != null -> TextRange(old.value.selection.start.coerceAtMost(part.text.length), old.value.selection.end.coerceAtMost(part.text.length))
                    cursorAtEnd && i == parts.lastIndex -> TextRange(part.text.length)
                    else -> TextRange.Zero
                }
                Block.Text(old?.key ?: nextKey(), TextFieldValue(part.text, selection))
            }
        }
    }
}

/**
 * The writing surface used by Write and by an opened entry: text with photos between
 * the paragraphs, exactly where they were added.
 *
 * Under the hood the entry is still ONE piece of text; each photo is a line
 * {{photo:id}} in it (see Photos.kt). Here that text is cut into text fields and photos.
 */
@Composable
fun EntryEditor(
    text: String,
    onTextChange: (String) -> Unit,
    textStyle: TextStyle,
    /** Size of the first line (the title). */
    titleSize: TextUnit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    /** Goes up by one each time the Photo button is pressed. */
    addPhotoSignal: Int = 0,
    /** Put the cursor at the end of the text when the screen opens. */
    focusOnStart: Boolean = false,
    /** Tall last text area, so clicking anywhere below the text starts typing there. */
    minLastHeight: Dp = 160.dp,
    photos: PhotoStore = koinInject(),
) {
    var blocks by remember { mutableStateOf(blocksFor(text, null, cursorAtEnd = true)) }
    var focusedKey by remember { mutableStateOf<Long?>(null) }
    var focusKeyRequest by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var undoText by remember { mutableStateOf<String?>(null) }
    val requesters = remember { HashMap<Long, FocusRequester>() }
    val scope = rememberCoroutineScope()
    val latestText by rememberUpdatedState(text)
    val titleLook = remember(titleSize) { FirstLineAsTitle(titleSize) }

    // Texts we sent up that the screen may still echo back a moment later. Those must
    // not rebuild the blocks (that would undo the newest keystrokes).
    val published = remember { ArrayDeque<String>() }
    val seen = remember { arrayOf(text) }
    if (text != seen[0]) {
        seen[0] = text
        val echo = published.indexOf(text)
        if (echo >= 0) {
            repeat(echo + 1) { published.removeFirst() }
        } else if (text != blocks.toText()) {
            // Really changed somewhere else (sync, another screen, Undo): rebuild.
            published.clear()
            blocks = blocksFor(text, blocks)
        }
    }

    fun publish(next: List<Block>) {
        blocks = next
        val joined = next.toText()
        if (joined != latestText) {
            published.addLast(joined)
            if (published.size > 50) published.removeFirst()
            onTextChange(joined)
        }
    }

    fun insertPhotos(ids: List<String>) {
        if (ids.isEmpty()) return
        val current = blocks
        // Where the cursor is (or was last); otherwise the end of the entry.
        val index = current.indexOfFirst { it.key == focusedKey }.takeIf { it >= 0 && current[it] is Block.Text }
            ?: current.indexOfLast { it is Block.Text }
        val block = current[index] as Block.Text
        val value = block.value.text
        var cut = if (block.key == focusedKey) block.value.selection.start else value.length
        // Never above or inside the title line.
        if (index == 0) cut = maxOf(cut, value.indexOf('\n').let { if (it < 0) value.length else it })
        val before = value.substring(0, cut).removeSuffix("\n")
        val after = value.substring(cut).removePrefix("\n")

        val inserted = mutableListOf<Block>(Block.Text(block.key, TextFieldValue(before, TextRange(before.length))))
        ids.forEachIndexed { i, id ->
            inserted += Block.Photo(nextKey(), id)
            if (i < ids.lastIndex) inserted += Block.Text(nextKey(), TextFieldValue(""))
        }
        val afterBlock = Block.Text(nextKey(), TextFieldValue(after, TextRange.Zero))
        inserted += afterBlock
        publish(current.take(index) + inserted + current.drop(index + 1))
        focusKeyRequest = afterBlock.key // keep writing right below the photo
    }

    fun removePhoto(index: Int) {
        val current = blocks
        val a = (current.getOrNull(index - 1) as? Block.Text) ?: return
        val b = (current.getOrNull(index + 1) as? Block.Text) ?: return
        undoText = current.toText()
        val merged = when {
            a.value.text.isEmpty() -> b.value.text
            b.value.text.isEmpty() -> a.value.text
            else -> a.value.text + "\n" + b.value.text
        }
        val joinedBlock = Block.Text(a.key, TextFieldValue(merged, TextRange(a.value.text.length)))
        publish(current.take(index - 1) + joinedBlock + current.drop(index + 2))
    }

    val pick = rememberPhotoPicker { picked ->
        scope.launch {
            val room = MAX_PHOTOS_PER_ENTRY - photoIds(latestText).size
            if (room <= 0) {
                status = "An entry can have up to $MAX_PHOTOS_PER_ENTRY photos."
                return@launch
            }
            status = if (picked.size == 1) "Adding photo…" else "Adding ${minOf(picked.size, room)} photos…"
            val ids = picked.take(room).mapNotNull { photos.add(it)?.id }
            insertPhotos(ids)
            status = when {
                ids.size < minOf(picked.size, room) -> "Some files couldn't be opened as photos."
                picked.size > room -> "Added $room. An entry can have up to $MAX_PHOTOS_PER_ENTRY photos."
                else -> null
            }
        }
    }
    // Only presses that happen while this editor is on screen (not an old count).
    val startSignal = remember { addPhotoSignal }
    LaunchedEffect(addPhotoSignal) { if (addPhotoSignal != startSignal) pick() }
    LaunchedEffect(status) {
        if (status != null && !status!!.endsWith("…")) {
            delay(5_000)
            status = null
        }
    }
    LaunchedEffect(undoText) {
        if (undoText != null) {
            delay(6_000)
            undoText = null
        }
    }
    LaunchedEffect(focusKeyRequest) {
        val key = focusKeyRequest ?: return@LaunchedEffect
        runCatching { requesters[key]?.requestFocus() }
        focusKeyRequest = null
    }
    LaunchedEffect(Unit) {
        if (focusOnStart) blocks.lastOrNull { it is Block.Text }?.let { focusKeyRequest = it.key }
    }

    val isEmpty = text.isEmpty()
    Column(modifier.animateContentSize(Motion.standard())) {
        blocks.forEachIndexed { index, block ->
            key(block.key) {
                when (block) {
                    is Block.Text -> {
                        val requester = requesters.getOrPut(block.key) { FocusRequester() }
                        val last = index == blocks.lastIndex
                        BasicTextField(
                            value = block.value,
                            onValueChange = { v ->
                                val i = blocks.indexOfFirst { it.key == block.key }
                                if (i >= 0) publish(blocks.toMutableList().also { it[i] = Block.Text(block.key, v) })
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (last) Modifier.heightIn(min = minLastHeight) else Modifier)
                                .focusRequester(requester)
                                .onFocusChanged { if (it.isFocused) focusedKey = block.key }
                                .semantics { contentDescription = if (index == 0) "Journal entry. The first line is the title." else "Text after a photo" },
                            textStyle = textStyle,
                            cursorBrush = SolidColor(JournalColors.Accent),
                            visualTransformation = if (index == 0) titleLook else VisualTransformation.None,
                            decorationBox = { field ->
                                Box {
                                    if (index == 0 && isEmpty && placeholder != null) {
                                        Text(placeholder, style = textStyle.copy(color = JournalColors.Muted.copy(alpha = 0.55f)))
                                    }
                                    field()
                                }
                            },
                        )
                    }
                    is Block.Photo -> PhotoInEntry(block.id, photos, onRemove = { removePhoto(blocks.indexOfFirst { it.key == block.key }) })
                }
            }
        }
    }

    // Small notes under the text: "Adding photo…", "Photo removed · Undo".
    AnimatedVisibility(visible = status != null || undoText != null, enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick())) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (undoText != null) {
                Text("Photo removed", color = JournalColors.Muted, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Undo",
                    color = JournalColors.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) {
                        undoText?.let { saved ->
                            published.clear()
                            onTextChange(saved)
                        }
                        undoText = null
                    }.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            } else {
                Text(status.orEmpty(), color = JournalColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

/** A photo inside the editor: the picture, with a × to remove it. */
@Composable
private fun PhotoInEntry(id: String, photos: PhotoStore, onRemove: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        PhotoView(id, photos)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xB80B0E17))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove this photo" },
            contentAlignment = Alignment.Center,
        ) { LineIcon(Glyph.Close, JournalColors.Text, size = 14.dp, strokeWidth = 2.4f) }
    }
}

/**
 * Draws one photo at its own shape (never taller than [maxHeight]). While the file is
 * still on its way from another device, a shimmering placeholder of the same shape shows.
 */
@Composable
fun PhotoView(
    id: String,
    photos: PhotoStore = koinInject(),
    modifier: Modifier = Modifier,
    maxHeight: Dp = 520.dp,
    corner: Dp = 14.dp,
) {
    val revision by photos.revision.collectAsState()
    val meta by produceState<Photo?>(null, id, revision) { value = photos.get(id) }
    val image by produceState<ImageBitmap?>(null, id, revision) { value = photos.image(id) }
    val ratio = (
        meta?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
            ?: image?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
            ?: 1.5f
        ).coerceIn(0.5f, 3f)
    val shape = RoundedCornerShape(corner)

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val sized = Modifier.heightIn(max = maxHeight).fillMaxWidth().aspectRatio(ratio).clip(shape)
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = "Photo", modifier = sized, contentScale = ContentScale.Crop)
        } else {
            Box(sized.shimmer().semantics { contentDescription = "Photo loading" })
        }
    }
}

/** A saved entry's text with its photos in place, read-only (Timeline). */
@Composable
fun EntryBodyView(body: String, style: TextStyle, modifier: Modifier = Modifier) {
    Column(modifier) {
        for (part in splitParts(body)) {
            when (part) {
                is EntryPart.Text -> if (part.text.isNotBlank()) Text(part.text.trim('\n'), style = style)
                is EntryPart.Photo -> PhotoView(part.id, modifier = Modifier.padding(vertical = 14.dp), maxHeight = 420.dp)
            }
        }
    }
}

/** A small square crop of a photo (list cards). */
@Composable
fun PhotoThumb(id: String, modifier: Modifier = Modifier, photos: PhotoStore = koinInject()) {
    val revision by photos.revision.collectAsState()
    val image by produceState<ImageBitmap?>(null, id, revision) { value = photos.image(id) }
    val sized = modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
    val bitmap = image
    if (bitmap != null) Image(bitmap, contentDescription = "Photo", modifier = sized, contentScale = ContentScale.Crop)
    else Box(sized.shimmer())
}
