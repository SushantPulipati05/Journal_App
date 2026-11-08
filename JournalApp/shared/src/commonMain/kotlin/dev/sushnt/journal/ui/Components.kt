package dev.sushnt.journal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class HomeTab { Write, Journals }

/** The floating pill at the bottom with the app's only two tabs. */
@Composable
fun TabBar(current: HomeTab, onSelect: (HomeTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .shadow(16.dp, CircleShape)
            .background(JournalColors.Raised, CircleShape)
            .border(1.dp, JournalColors.BorderStrong, CircleShape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TabButton("Write", Glyph.Pen, current == HomeTab.Write) { onSelect(HomeTab.Write) }
        TabButton("Journals", Glyph.Book, current == HomeTab.Journals) { onSelect(HomeTab.Journals) }
    }
}

@Composable
private fun TabButton(label: String, glyph: Glyph, isSelected: Boolean, onClick: () -> Unit) {
    val color = if (isSelected) JournalColors.Accent else Color(0xFF9AA3B8)
    Row(
        Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (isSelected) JournalColors.AccentContainer else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = isSelected }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(glyph, color)
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

/** The round person button in the top right corner that opens Settings. */
@Composable
fun SettingsButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(JournalColors.Raised)
            .border(1.dp, JournalColors.BorderStrong, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Account and settings" },
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(Glyph.Person, Color(0xFFC9CEDB))
    }
}

/** Small uppercase label used for list sections ("TODAY", "ACCOUNT"...). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(horizontal = 4.dp),
        color = JournalColors.Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

val CardShape = RoundedCornerShape(18.dp)

/** The rounded, bordered card surface used across the app. */
fun Modifier.card(): Modifier = this
    .clip(CardShape)
    .background(JournalColors.Card)
    .border(1.dp, JournalColors.Border, CardShape)

/** A 44 dp icon-only button. [description] is what screen readers say. */
@Composable
fun IconAction(glyph: Glyph, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(glyph, tint)
    }
}
