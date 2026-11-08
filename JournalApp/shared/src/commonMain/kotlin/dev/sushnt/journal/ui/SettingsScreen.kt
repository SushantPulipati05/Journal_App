package dev.sushnt.journal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.sushnt.journal.data.EntryTextSize
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(onClose: () -> Unit, showBack: Boolean = true, vm: SettingsViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val syncMessage by vm.syncMessage.collectAsState()
    val lastSyncedAt by vm.lastSyncedAt.collectAsState()
    var askDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }
    PlatformBackHandler(enabled = showBack, onBack = onClose)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (showBack) {
            TextButton(onClick = onClose, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                LineIcon(Glyph.Back, JournalColors.Text)
                Spacer(Modifier.width(4.dp))
                Text("Back", color = JournalColors.Text, fontWeight = FontWeight.Medium)
            }
        } else {
            Spacer(Modifier.height(32.dp))
        }

        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                "Settings",
                color = JournalColors.TextStrong,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            // ---------- account card ----------
            Row(Modifier.fillMaxWidth().card().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(JournalColors.AccentContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (state.name ?: state.email ?: "?").take(1).uppercase(),
                        color = JournalColors.Accent,
                        fontFamily = FontFamily.Serif,
                        fontSize = 24.sp,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.name ?: "Your account", color = JournalColors.TextStrong, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        state.email ?: "Loading…",
                        color = JournalColors.Muted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            state.error?.let { Text(it, color = JournalColors.Danger, modifier = Modifier.padding(horizontal = 4.dp)) }

            // ---------- writing ----------
            Group("Writing") {
                SwitchRow(
                    title = "Show daily streak",
                    subtitle = "A gentle nudge on the Journals tab",
                    checked = prefs.showStreak,
                    onChange = { on -> vm.updatePrefs { it.copy(showStreak = on) } },
                )
                Divider()
                TextSizePicker(
                    current = prefs.textSize,
                    serif = prefs.serifFont,
                    onPick = { size -> vm.updatePrefs { it.copy(textSize = size) } },
                )
                Divider()
                SwitchRow(
                    title = "Continue last entry",
                    subtitle = "Reopening within 30 minutes brings back what you were writing",
                    checked = prefs.continueLastEntry,
                    onChange = { on -> vm.updatePrefs { it.copy(continueLastEntry = on) } },
                )
                Divider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Writing font", color = JournalColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Segmented(
                        options = listOf("Serif" to FontFamily.Serif, "Sans" to FontFamily.SansSerif),
                        selectedIndex = if (prefs.serifFont) 0 else 1,
                        onSelect = { i -> vm.updatePrefs { it.copy(serifFont = i == 0) } },
                    )
                }
            }

            // ---------- sync ----------
            Group("Sync") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val healthy = syncMessage == null
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (healthy) JournalColors.Success else JournalColors.Warning))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                syncing -> "Syncing…"
                                healthy -> "All entries synced"
                                else -> "Not synced"
                            },
                            color = JournalColors.Text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            syncMessage ?: lastSyncedAt?.let { "Last sync: ${relativeLabel(it)}" } ?: "Syncs automatically",
                            color = JournalColors.Muted,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = vm::syncNow, enabled = !syncing) { Text("Sync now", fontWeight = FontWeight.SemiBold) }
                }
            }

            // ---------- about ----------
            Group("About") {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Version", color = JournalColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("1.0.0", color = JournalColors.Muted, fontSize = 15.sp)
                }
            }

            // ---------- sign out / delete ----------
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("Sign out", JournalColors.Text, JournalColors.Raised, JournalColors.BorderStrong, enabled = !state.busy, onClick = vm::requestSignOut)
                BigButton("Delete my account", JournalColors.Danger, Color.Transparent, JournalColors.DangerBorder, enabled = !state.busy) { askDelete = true }
                Text(
                    "Deleting your account erases it and every entry on all your devices. This can't be undone.",
                    color = JournalColors.Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    state.confirmSignOut?.let { count ->
        AlertDialog(
            onDismissRequest = vm::cancelSignOut,
            title = { Text("Sign out?") },
            text = { Text("$count change(s) haven't reached the server yet and will be lost if you sign out now.") },
            confirmButton = { TextButton(onClick = vm::confirmSignOut) { Text("Sign out anyway") } },
            dismissButton = { TextButton(onClick = vm::cancelSignOut) { Text("Cancel") } },
        )
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete your account?") },
            text = { Text("This can't be undone. Your account and all your entries will be erased.") },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    vm.deleteAccount()
                }) { Text("Delete forever", color = JournalColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
    }
}

/** A labelled card holding rows. */
@Composable
private fun Group(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        Column(Modifier.fillMaxWidth().card(), content = content)
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = JournalColors.Border)

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = JournalColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = JournalColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null, // the whole row handles the tap
            colors = SwitchDefaults.colors(
                checkedTrackColor = JournalColors.Accent,
                checkedThumbColor = JournalColors.TextStrong,
                uncheckedTrackColor = Color(0xFF2A3247),
                uncheckedThumbColor = JournalColors.Muted,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Five "Aa" buttons from small to large, plus a live preview of the writing text. */
@Composable
private fun TextSizePicker(current: EntryTextSize, serif: Boolean, onPick: (EntryTextSize) -> Unit) {
    val font = writingFont(serif)
    val buttonSizes = listOf(13, 16, 19, 22, 26) // how big "Aa" looks on each button
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", color = JournalColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(current.label, color = JournalColors.Muted, fontSize = 13.sp)
        }
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(JournalColors.Inset)
                .border(1.dp, JournalColors.Border, shape)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EntryTextSize.entries.forEachIndexed { i, size ->
                val isSelected = size == current
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) JournalColors.AccentContainer else Color.Transparent)
                        .clickable(role = Role.RadioButton) { onPick(size) }
                        .semantics {
                            contentDescription = size.label
                            selected = isSelected
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Aa",
                        color = if (isSelected) JournalColors.Accent else JournalColors.Preview,
                        fontFamily = font,
                        fontSize = buttonSizes[i].sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Text(
            "It started raining halfway down the hill and I didn't even mind.",
            color = JournalColors.Body,
            fontFamily = font,
            fontSize = current.sp.sp,
            lineHeight = (current.sp * 1.55f).sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(JournalColors.Inset)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Text("Changes the writing and reading text, not the menus.", color = JournalColors.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Segmented(options: List<Pair<String, FontFamily>>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JournalColors.Inset)
            .border(1.dp, JournalColors.Border, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { i, (label, family) ->
            val isSelected = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) JournalColors.AccentContainer else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .semantics { selected = isSelected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSelected) JournalColors.Accent else JournalColors.Preview,
                    fontFamily = family,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun BigButton(
    text: String,
    textColor: Color,
    background: Color,
    borderColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) textColor else JournalColors.Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
