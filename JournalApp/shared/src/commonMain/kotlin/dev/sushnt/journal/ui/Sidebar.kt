package dev.sushnt.journal.ui

import androidx.compose.animation.animateColorAsState
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

const val SIDEBAR_WIDTH = 236

/**
 * The desktop/tablet sidebar. Closing it (the button at the top, or Ctrl+\) hides it
 * completely; [ClosedSidebarBar] and the floating tab bar take over.
 */
@Composable
fun Sidebar(
    current: SidebarItem,
    entryCount: Int,
    streak: Int,
    showStreak: Boolean,
    sync: SyncLine,
    userName: String?,
    onClose: () -> Unit,
    onSelect: (SidebarItem) -> Unit,
) {
    Column(
        Modifier
            .width(SIDEBAR_WIDTH.dp)
            .fillMaxHeight()
            .background(JournalColors.Inset)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(JournalColors.Accent))
            Spacer(Modifier.width(10.dp))
            Text(
                "Journal",
                color = JournalColors.TextStrong,
                fontFamily = FontFamily.Serif,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconAction(Glyph.Panel, "Close sidebar", Color(0xFF9AA3B8), onClose)
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
        val inSettings = current == SidebarItem.Settings
        val shape = RoundedCornerShape(12.dp)
        val fill by animateColorAsState(if (inSettings) JournalColors.AccentContainer else Color.Transparent, Motion.standard(), label = "account fill")
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(shape)
                .background(fill)
                .border(1.dp, if (inSettings) JournalColors.StreakBorder else JournalColors.Border, shape)
                .clickable(role = Role.Button) { onSelect(SidebarItem.Settings) }
                .semantics { selected = inSettings }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(userName, size = 32)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(userName ?: "Your account", color = JournalColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Settings", color = if (inSettings) JournalColors.Accent else JournalColors.Muted, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/**
 * The only thing at the top while the sidebar is closed: a button to bring it back on
 * the left, the app's name, and your avatar (Settings) on the right. Nothing else.
 */
@Composable
fun ClosedSidebarBar(userName: String?, onOpenSidebar: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Glyph.Panel, "Open sidebar", Color(0xFF9AA3B8), onOpenSidebar)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(6.dp).clip(CircleShape).background(JournalColors.Accent))
        Spacer(Modifier.width(8.dp))
        Text("JOURNAL", color = JournalColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        Avatar(userName, size = 34, description = "Account and settings", onClick = onSettings)
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
        Text(label, color = color, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
        Text(trailing, color = if (isSelected) JournalColors.Accent else JournalColors.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

/** A round initial. Clickable only when [onClick] is given. */
@Composable
fun Avatar(name: String?, size: Int, description: String? = null, onClick: (() -> Unit)? = null) {
    var modifier = Modifier
        .size(size.dp)
        .clip(CircleShape)
        .background(JournalColors.AccentContainer)
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
