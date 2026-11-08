package dev.sushnt.journal.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Where the wide layout is. */
enum class SidebarItem { Write, Journals, Settings }

/** What the sidebar shows about sync, in one line. */
data class SyncLine(val text: String, val healthy: Boolean)

/**
 * The desktop/tablet sidebar. It can fold down to a narrow strip of icons
 * (the button at the top, or Ctrl+\), like the sidebar in Claude.
 */
@Composable
fun Sidebar(
    collapsed: Boolean,
    current: SidebarItem,
    entryCount: Int,
    streak: Int,
    showStreak: Boolean,
    sync: SyncLine,
    userName: String?,
    onToggle: () -> Unit,
    onSelect: (SidebarItem) -> Unit,
) {
    val width by animateDpAsState(if (collapsed) 72.dp else 236.dp, Motion.standard(), label = "sidebar width")
    // The panel's width slides; its contents cross-fade. The open layout is always laid out
    // at full width (and clipped), so text never squashes or wraps mid-animation.
    Box(Modifier.width(width).fillMaxHeight().background(JournalColors.Inset).clipToBounds()) {
    AnimatedContent(
        targetState = collapsed,
        transitionSpec = { fadeIn(tween(200, delayMillis = 100)) togetherWith fadeOut(tween(90)) },
        label = "sidebar content",
    ) { folded ->
    Column(
        Modifier
            .fillMaxHeight()
            .wrapContentWidth(align = Alignment.Start, unbounded = true)
            .width(if (folded) 72.dp else 236.dp)
            .padding(horizontal = if (folded) 14.dp else 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (folded) {
            IconAction(Glyph.Panel, "Open sidebar", Color(0xFF9AA3B8), onToggle)
            Spacer(Modifier.height(14.dp))
            RailButton(Glyph.Pen, "Write", current == SidebarItem.Write) { onSelect(SidebarItem.Write) }
            RailButton(Glyph.Book, "Journals", current == SidebarItem.Journals) { onSelect(SidebarItem.Journals) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .padding(start = 18.dp, bottom = 10.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (sync.healthy) JournalColors.Success else JournalColors.Warning)
                    .semantics { contentDescription = sync.text },
            )
            Avatar(userName, size = 44, selected = current == SidebarItem.Settings, description = "Settings") {
                onSelect(SidebarItem.Settings)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(JournalColors.Accent))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Journal",
                    color = JournalColors.TextStrong,
                    fontFamily = FontFamily.Serif,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                IconAction(Glyph.Panel, "Close sidebar", Color(0xFF9AA3B8), onToggle)
            }
            NavButton(Glyph.Pen, "Write", "Ctrl N", current == SidebarItem.Write) { onSelect(SidebarItem.Write) }
            NavButton(Glyph.Book, "Journals", entryCount.toString(), current == SidebarItem.Journals) { onSelect(SidebarItem.Journals) }

            Spacer(Modifier.weight(1f))

            if (showStreak && streak > 0) {
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(JournalColors.StreakCard)
                        .border(1.dp, JournalColors.StreakBorder, shape)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(JournalColors.AccentContainer),
                        contentAlignment = Alignment.Center,
                    ) { LineIcon(Glyph.Moon, JournalColors.Accent, size = 15.dp, strokeWidth = 2f) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            if (streak == 1) "1 day so far" else "$streak days in a row",
                            color = JournalColors.TextStrong,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Keep it going.", color = JournalColors.Preview, fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (sync.healthy) JournalColors.Success else JournalColors.Warning))
                Spacer(Modifier.width(8.dp))
                Text(sync.text, color = JournalColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val selected = current == SidebarItem.Settings
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(if (selected) JournalColors.AccentContainer else Color.Transparent)
                    .border(1.dp, if (selected) JournalColors.StreakBorder else JournalColors.Border, shape)
                    .clickable(role = Role.Button) { onSelect(SidebarItem.Settings) }
                    .semantics { this.selected = selected }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(userName, size = 32, selected = false, description = null, onClick = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(userName ?: "Your account", color = JournalColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Settings", color = if (selected) JournalColors.Accent else JournalColors.Muted, fontSize = 12.sp)
                }
            }
        }
    }
    }
    }
}

@Composable
private fun NavButton(glyph: Glyph, label: String, trailing: String, isSelected: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(if (isSelected) JournalColors.Accent else Color(0xFFC9CEDB), Motion.standard(), label = "nav ink")
    val fill by animateColorAsState(if (isSelected) JournalColors.AccentContainer else Color.Transparent, Motion.standard(), label = "nav fill")
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = isSelected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(glyph, color)
        Spacer(Modifier.width(12.dp))
        Text(label, color = color, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(trailing, color = if (isSelected) JournalColors.Accent else JournalColors.Muted, fontSize = 12.sp)
    }
}

/** A nav button with only an icon (folded sidebar). */
@Composable
private fun RailButton(glyph: Glyph, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (isSelected) JournalColors.AccentContainer else Color.Transparent, Motion.standard(), label = "rail fill")
    val ink by animateColorAsState(if (isSelected) JournalColors.Accent else Color(0xFFC9CEDB), Motion.standard(), label = "rail ink")
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(glyph, ink)
    }
}

/** A round initial. Clickable only when [onClick] is given. */
@Composable
private fun Avatar(name: String?, size: Int, selected: Boolean, description: String?, onClick: (() -> Unit)?) {
    var modifier = Modifier
        .size(size.dp)
        .clip(CircleShape)
        .background(JournalColors.AccentContainer)
    if (selected) modifier = modifier.border(1.dp, JournalColors.Accent, CircleShape)
    if (onClick != null) modifier = modifier.clickable(role = Role.Button, onClick = onClick)
    if (description != null) modifier = modifier.semantics { contentDescription = description }
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            (name?.firstOrNull() ?: '•').uppercase(),
            color = JournalColors.Accent,
            fontFamily = FontFamily.Serif,
            fontSize = (size * 0.45f).sp,
        )
    }
}
