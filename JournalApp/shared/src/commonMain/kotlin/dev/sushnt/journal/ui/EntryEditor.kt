package dev.sushnt.journal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import dev.sushnt.journal.data.PHOTO_MAX_PERCENT
import dev.sushnt.journal.data.PHOTO_MIN_PERCENT
import kotlin.math.roundToInt
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.runtime.SideEffect
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
    /** [widthPercent]: % of the text width once resized; null = default size. */
    data class Photo(override val key: Long, val id: String, val widthPercent: Int? = null) : Block
}

private var keyCounter = 0L
private fun nextKey() = ++keyCounter

private fun List<Block>.toText(): String = joinParts(map {
    when (it) {
        is Block.Text -> EntryPart.Text(it.value.text)
        is Block.Photo -> EntryPart.Photo(it.id, it.widthPercent)
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
            is EntryPart.Photo -> Block.Photo(if (sameShape) previous!![i].key else nextKey(), part.id, part.widthPercent)
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
    /** Size limits for photos in the text. Desktop keeps them small so there's room to write. */
    photoMaxHeight: Dp = 300.dp,
    photoMaxWidth: Dp = 480.dp,
    /**
     * After adding a photo, put the cursor right below it. Desktop: yes. Phone: no, because
     * that pops the keyboard up over the photo you just added; tap to keep writing.
     */
    refocusAfterPhoto: Boolean = true,
    /** Lets the screen around the editor put dictated words at the cursor. */
    handle: EditorHandle? = null,
    photos: PhotoStore = koinInject(),
) {
    var blocks by remember { mutableStateOf(blocksFor(text, null, cursorAtEnd = true)) }
    var focusedKey by remember { mutableStateOf<Long?>(null) }
    var focusKeyRequest by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var undoText by remember { mutableStateOf<String?>(null) }
    val requesters = remember { HashMap<Long, FocusRequester>() }
    val focusManager = LocalFocusManager.current
    /** The photo tapped for resizing (shows its side handles), if any. */
    var selectedPhoto by remember { mutableStateOf<Long?>(null) }
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
        // Blank lines next to a photo would only add empty space around it.
        val before = value.substring(0, cut).trimEnd('\n')
        val after = value.substring(cut).trimStart('\n')

        val inserted = mutableListOf<Block>(Block.Text(block.key, TextFieldValue(before, TextRange(before.length))))
        ids.forEachIndexed { i, id ->
            inserted += Block.Photo(nextKey(), id)
            if (i < ids.lastIndex) inserted += Block.Text(nextKey(), TextFieldValue(""))
        }
        val afterBlock = Block.Text(nextKey(), TextFieldValue(after, TextRange.Zero))
        inserted += afterBlock
        publish(current.take(index) + inserted + current.drop(index + 1))
        if (refocusAfterPhoto) {
            focusKeyRequest = afterBlock.key // keep writing right below the photo
        } else {
            focusedKey = null
            focusManager.clearFocus() // keyboard down: the photo stays in view
        }
    }

    fun jumpToTextBefore(key: Long) {
        val i = blocks.indexOfFirst { it.key == key }
        val previous = (0 until i).reversed().map { blocks[it] }.firstOrNull { it is Block.Text } as? Block.Text ?: return
        val end = previous.value.text.length
        blocks = blocks.map { if (it.key == previous.key) Block.Text(it.key, previous.value.copy(selection = TextRange(end))) else it }
        focusKeyRequest = previous.key
    }

    /**
     * Dictation: words arrive while you speak ([final] = false, they may still change) and
     * then for good ([final] = true). They go where the cursor is (or at the end), and each
     * new guess replaces the previous one, so the text "settles" as you talk.
     */
    val spot = remember { DictationSpot() }
    fun dictate(chunk: String, final: Boolean) {
        val current = blocks
        var index = current.indexOfFirst { it.key == spot.blockKey }
        if (index < 0 || current[index] !is Block.Text) {
            // First words: start at the cursor, or at the end of the entry.
            index = current.indexOfFirst { it.key == focusedKey }.takeIf { it >= 0 && current[it] is Block.Text }
                ?: current.indexOfLast { it is Block.Text }
            val b = current[index] as Block.Text
            spot.blockKey = b.key
            spot.start = if (b.key == focusedKey) b.value.selection.end else b.value.text.length
            spot.length = 0
            spot.newLineFirst = false
            if (index == 0) {
                // The first line is the title. Spoken words belong in the entry, not in the title:
                // start on a new line below it (or at the end of the text if there's more already).
                val firstLineEnd = b.value.text.indexOf('\n')
                if (firstLineEnd < 0) {
                    spot.start = b.value.text.length
                    spot.newLineFirst = true
                } else if (spot.start <= firstLineEnd) {
                    spot.start = b.value.text.length
                }
            }
        }
        val block = current[index] as Block.Text
        val old = block.value.text
        val start = spot.start.coerceIn(0, old.length)
        val end = (start + spot.length).coerceIn(start, old.length)
        val needsSpace = start > 0 && !old[start - 1].isWhitespace()
        val words = chunk.trim()
        val lead = when {
            spot.newLineFirst -> "\n"
            needsSpace -> " "
            else -> ""
        }
        val inserted = if (words.isEmpty()) "" else lead + words.replaceFirstChar { if (lead == "\n" || start == 0 || old.getOrNull(start - 1) == '\n') it.uppercaseChar() else it }
        val next = old.substring(0, start) + inserted + old.substring(end)
        val cursor = start + inserted.length
        publish(current.toMutableList().also { it[index] = Block.Text(block.key, TextFieldValue(next, TextRange(cursor))) })
        if (final) {
            spot.start = cursor // the next sentence goes right after this one
            spot.length = 0
            if (inserted.isNotEmpty()) spot.newLineFirst = false
        } else {
            spot.length = inserted.length
        }
    }
    SideEffect {
        handle?.onDictate = ::dictate
        handle?.onEnd = {
            spot.blockKey = null
            spot.length = 0
        }
    }

    fun resizePhoto(key: Long, percent: Int) {
        publish(blocks.map { if (it is Block.Photo && it.key == key) it.copy(widthPercent = percent) else it })
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

    fun addPicked(picked: List<ByteArray>) {
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
    val pick = rememberPhotoPicker { picked -> addPicked(picked) }
    // null where there's no camera (desktop): then the button opens the file picker directly.
    val takePhoto = rememberCameraCapture { taken -> addPicked(listOf(taken)) }
    var choosingSource by remember { mutableStateOf(false) }

    // Only presses that happen while this editor is on screen (not an old count).
    val startSignal = remember { addPhotoSignal }
    LaunchedEffect(addPhotoSignal) {
        if (addPhotoSignal == startSignal) return@LaunchedEffect
        if (takePhoto != null) {
            focusManager.clearFocus() // keyboard down so the choice sheet is fully visible
            choosingSource = true
        } else {
            pick()
        }
    }
    if (choosingSource) {
        PhotoSourceSheet(
            onCamera = {
                choosingSource = false
                takePhoto?.invoke()
            },
            onGallery = {
                choosingSource = false
                pick()
            },
            onDismiss = { choosingSource = false },
        )
    }
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
    Column(modifier) {
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
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        focusedKey = block.key
                                        selectedPhoto = null // typing again: put the resize handles away
                                    }
                                }
                                // Backspace at the very start of the text below a photo: hop back to
                                // the end of the text above the photo (the photo itself stays; use ×).
                                .onPreviewKeyEvent { event ->
                                    val atStart = block.value.selection.collapsed && block.value.selection.start == 0
                                    if (index > 0 && atStart && event.type == KeyEventType.KeyDown && event.key == Key.Backspace) {
                                        jumpToTextBefore(block.key)
                                        true
                                    } else {
                                        false
                                    }
                                }
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
                    is Block.Photo -> PhotoInEntry(
                        id = block.id,
                        photos = photos,
                        photoMaxHeight = photoMaxHeight,
                        photoMaxWidth = photoMaxWidth,
                        widthPercent = block.widthPercent,
                        selected = selectedPhoto == block.key,
                        onSelect = {
                            selectedPhoto = if (selectedPhoto == block.key) null else block.key
                            if (selectedPhoto != null) focusManager.clearFocus()
                        },
                        onResize = { percent -> resizePhoto(block.key, percent) },
                        onRemove = {
                            selectedPhoto = null
                            removePhoto(blocks.indexOfFirst { it.key == block.key })
                        },
                    )
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

/**
 * A photo inside the editor. Tap it to select it: two handles appear on its sides.
 * Dragging either handle resizes it from both sides at once, so it stays centred and keeps
 * its shape. The new size is saved in the entry text ({{photo:id|60}} = 60 % wide).
 */
@Composable
private fun PhotoInEntry(
    id: String,
    photos: PhotoStore,
    photoMaxHeight: Dp,
    photoMaxWidth: Dp,
    widthPercent: Int?,
    selected: Boolean,
    onSelect: () -> Unit,
    onResize: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val available by rememberUpdatedState(constraints.maxWidth.toFloat())
        // While dragging, the size lives here; it's saved to the text when the finger lifts.
        var live by remember(widthPercent) { mutableStateOf(widthPercent?.toFloat()) }
        var shownPx by remember { mutableStateOf(0f) }
        val save by rememberUpdatedState(onResize)

        fun grow(byPx: Float) {
            val start = live?.let { it / 100f * available } ?: shownPx
            val min = available * PHOTO_MIN_PERCENT / 100f
            val next = (start + 2 * byPx).coerceIn(min, available) // both sides move: stays centred
            live = next / available * 100f
        }
        fun finish() {
            live?.let { save(it.roundToInt().coerceIn(PHOTO_MIN_PERCENT, PHOTO_MAX_PERCENT)) }
        }

        PhotoView(
            id,
            photos,
            maxHeight = photoMaxHeight, // (plain maxWidth/maxHeight here would mean the space available)
            maxWidth = photoMaxWidth,
            widthFraction = live?.div(100f),
            onClick = onSelect,
        ) {
            Box(Modifier.matchParentSize().onSizeChanged { shownPx = it.width.toFloat() })
            if (selected) {
                Box(Modifier.matchParentSize().border(2.dp, JournalColors.Accent, RoundedCornerShape(14.dp)))
                ResizeHandle(Modifier.align(Alignment.CenterStart), onDrag = { dx -> grow(-dx) }, onDone = ::finish)
                ResizeHandle(Modifier.align(Alignment.CenterEnd), onDrag = { dx -> grow(dx) }, onDone = ::finish)
            }
            // The × sits on the photo's own corner, whatever its shape.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xB80B0E17))
                    .clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = "Remove this photo" },
                contentAlignment = Alignment.Center,
            ) { LineIcon(Glyph.Close, JournalColors.Text, size = 13.dp, strokeWidth = 2.4f) }
        }
    }
}

/** A white pill on the photo's edge; drag it sideways to resize. Big touch area, small look. */
@Composable
private fun ResizeHandle(modifier: Modifier, onDrag: (Float) -> Unit, onDone: () -> Unit) {
    val drag by rememberUpdatedState(onDrag)
    val done by rememberUpdatedState(onDone)
    Box(
        modifier
            .size(width = 28.dp, height = 64.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { done() },
                    onDragCancel = { done() },
                ) { change, dx ->
                    change.consume()
                    drag(dx)
                }
            }
            .semantics { contentDescription = "Drag to resize the photo" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 6.dp, height = 40.dp)
                .shadow(4.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
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
    maxHeight: Dp = 320.dp,
    /** Photos never get wider than this, even on a big desktop window. */
    maxWidth: Dp = 560.dp,
    corner: Dp = 14.dp,
    /** Resized photos: this share of the text width (0.2–1). null = the default size above. */
    widthFraction: Float? = null,
    /** Unpack the photo at about this many pixels (small previews save lots of memory). null = full size. */
    decodeMaxSide: Int? = null,
    onClick: (() -> Unit)? = null,
    /** Drawn on top of the photo, inside its exact bounds (e.g. the × button). */
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val revision by photos.revision.collectAsState()
    val meta by produceState<Photo?>(null, id, revision) { value = photos.get(id) }
    val image by produceState<ImageBitmap?>(null, id, revision, decodeMaxSide) { value = photos.image(id, decodeMaxSide) }
    // The real picture's own shape first (so it can never look stretched),
    // then the size we stored, then a guess while it's still downloading.
    val ratio = (
        image?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
            ?: meta?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
            ?: 1.5f
        ).coerceIn(0.2f, 5f)
    val shape = RoundedCornerShape(corner)

    // Work out the exact size ourselves: as wide as the text, but never taller than
    // [maxHeight] (a tall photo gets narrower instead), keeping the photo's own shape.
    // (Inside BoxWithConstraints, maxWidth/maxHeight mean the space available.)
    val tallest = maxHeight
    val widest = maxWidth
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val widthCap = minOf(maxWidth, widest)
        val bitmap = image
        val tap = if (onClick != null) {
            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        } else {
            Modifier
        }
        if (bitmap != null && widthFraction != null) {
            // Resized: exactly this share of the width; the height follows the photo's shape.
            val width = maxWidth * widthFraction.coerceIn(0.2f, 1f)
            Box(Modifier.size(width, width / ratio)) {
                Image(bitmap, contentDescription = "Photo", modifier = Modifier.fillMaxSize().clip(shape).then(tap), contentScale = ContentScale.Fit)
                overlay?.invoke(this)
            }
        } else if (bitmap != null) {
            // The picture sizes itself from its own pixels (Fit inside the limits), and this
            // Box shrinks to exactly that size, so the overlay (×) lands on the photo's corner.
            Box {
                Image(
                    bitmap,
                    contentDescription = "Photo",
                    modifier = Modifier.sizeIn(maxWidth = widthCap, maxHeight = tallest).clip(shape).then(tap),
                    contentScale = ContentScale.Fit,
                )
                overlay?.invoke(this)
            }
        } else {
            // Still downloading: a shimmering box of the size the photo will have.
            val width = widthFraction?.let { maxWidth * it.coerceIn(0.2f, 1f) } ?: minOf(widthCap, tallest * ratio)
            Box(Modifier.size(width, width / ratio).clip(shape).shimmer().semantics { contentDescription = "Photo loading" })
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
                is EntryPart.Photo -> PhotoView(
                    part.id,
                    modifier = Modifier.padding(vertical = 14.dp),
                    maxHeight = 320.dp,
                    widthFraction = part.widthPercent?.div(100f),
                )
            }
        }
    }
}

/** A small square crop of a photo (list cards). */
@Composable
fun PhotoThumb(id: String, modifier: Modifier = Modifier, photos: PhotoStore = koinInject()) {
    val revision by photos.revision.collectAsState()
    // Thumbnails are small squares: unpack at 320 px, not the full 1600 px photo.
    val image by produceState<ImageBitmap?>(null, id, revision) { value = photos.image(id, maxSide = 320) }
    val sized = modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
    val bitmap = image
    if (bitmap != null) Image(bitmap, contentDescription = "Photo", modifier = sized, contentScale = ContentScale.Crop)
    else Box(sized.shimmer())
}

/** Where dictated words are going: which text block, from where, and how long the current guess is. */
private class DictationSpot {
    var blockKey: Long? = null
    var start = 0
    var length = 0
    /** Start below the title line (the words go on a new line). */
    var newLineFirst = false
}

/** A small remote control for an [EntryEditor], used by the mic button around it. */
class EditorHandle {
    internal var onDictate: ((String, Boolean) -> Unit)? = null
    internal var onEnd: (() -> Unit)? = null

    /** Put dictated words at the cursor. Not final = a guess that the next call replaces. */
    fun dictate(text: String, final: Boolean) {
        onDictate?.invoke(text, final)
    }

    /** Dictation stopped: the next time starts fresh at the cursor. */
    fun endDictation() {
        onEnd?.invoke()
    }
}
