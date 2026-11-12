package dev.sushnt.journal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.sushnt.journal.data.AppPrefs
import dev.sushnt.journal.data.Entry
import dev.sushnt.journal.ui.Motion.fadeThrough

/**
 * An entry opened from the desktop Journals page: a centred sheet over the dimmed
 * page instead of a split view. Click outside, press Esc or × to close; the arrows
 * step to the newer/older entry. The text is editable, same as everywhere else.
 */
@Composable
fun EntrySheet(
    /** The open entry, or null when the sheet is closed. */
    entry: Entry?,
    /** The entries in the order the page shows them (newest first), for the arrows. */
    order: List<Entry>,
    prefs: AppPrefs,
    onSelect: (String?) -> Unit,
    onSaveText: (id: String, text: String) -> Unit,
    onDelete: (Entry) -> Unit,
) {
    // Keep showing the last entry while the sheet animates away.
    val last = remember { arrayOfNulls<Entry>(1) }
    if (entry != null) last[0] = entry
    val display = entry ?: last[0]

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = entry != null,
            enter = fadeIn(Motion.standard()),
            exit = fadeOut(Motion.standard()),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF05070C).copy(alpha = 0.62f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(null) },
            )
        }

        AnimatedVisibility(
            visible = entry != null,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(Motion.standard()) +
                scaleIn(tween(Motion.MEDIUM, easing = Motion.Emphasized), initialScale = 0.96f) +
                slideInVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { it / 24 },
            exit = fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = 0.97f),
        ) {
            val shape = RoundedCornerShape(22.dp)
            Box(
                Modifier
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .shadow(40.dp, shape)
                    .clip(shape)
                    .background(Color(0xFF10152A))
                    .border(1.dp, JournalColors.BorderStrong, shape)
                    // Clicks inside the sheet must not reach the backdrop (which closes it).
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                if (display != null) {
                    // Stepping to another entry cross-fades the sheet's content.
                    AnimatedContent(
                        targetState = display,
                        contentKey = { it.id },
                        transitionSpec = { fadeThrough() },
                        label = "sheet entry",
                    ) { shown ->
                        val index = order.indexOfFirst { it.id == shown.id }
                        val newer = order.getOrNull(index - 1).takeIf { index > 0 }
                        val older = order.getOrNull(index + 1).takeIf { index >= 0 }
                        key(shown.id) {
                            // key(): each entry has its own editing state, so text never leaks between entries.
                            EntryScreen(
                                entry = shown,
                                prefs = prefs,
                                onBack = { onSelect(null) },
                                onSaveText = { text -> onSaveText(shown.id, text) },
                                onDelete = { onDelete(shown) },
                                showBack = false,
                                sheet = SheetControls(
                                    onNewer = newer?.let { e -> { onSelect(e.id) } },
                                    onOlder = older?.let { e -> { onSelect(e.id) } },
                                    onClose = { onSelect(null) },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
