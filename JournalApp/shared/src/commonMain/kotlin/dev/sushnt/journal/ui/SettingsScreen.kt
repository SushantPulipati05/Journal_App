@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.sushnt.journal.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.remember
import org.koin.compose.koinInject
import dev.sushnt.journal.data.SpeechModel
import dev.sushnt.journal.data.SpeechModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
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
import dev.sushnt.journal.data.DictationLanguages
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
    var trashOpen by rememberSaveable { mutableStateOf(false) }
    val entries: EntriesViewModel = koinViewModel()
    val trash by entries.trash.collectAsState()

    LaunchedEffect(Unit) { vm.load() }
    if (trashOpen) {
        TrashScreen(onBack = { trashOpen = false }, vm = entries)
        return
    }
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
            if (state.profileSkeleton) Row(Modifier.fillMaxWidth().card().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonCircle(52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SkeletonBlock(width = 130.dp, height = 15.dp)
                    SkeletonBlock(width = 190.dp, height = 11.dp)
                }
            } else Row(Modifier.fillMaxWidth().card().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        state.email ?: "Couldn't load your email",
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

            // ---------- dictation ----------
            Group("Dictation") {
                Text(
                    "Dictation runs on this device with Whisper, so it works offline. Pick the language you speak, " +
                        "or let it detect it.",
                    color = JournalColors.Muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DictationLanguages.forEach { (code, label) ->
                        val chosen = prefs.dictationLanguage == code
                        Text(
                            label,
                            color = if (chosen) JournalColors.Accent else JournalColors.Text,
                            fontSize = 14.sp,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (chosen) JournalColors.AccentContainer else JournalColors.Border.copy(alpha = 0.5f))
                                .clickable(role = Role.RadioButton) { vm.updatePrefs { it.copy(dictationLanguage = code) } }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                Divider()
                Text(
                    "Speech model",
                    color = JournalColors.Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                val speechModels = koinInject<SpeechModels>()
                var modelsChanged by remember { mutableStateOf(0) }
                val activeModel = SpeechModel.fromId(prefs.speechModel) ?: defaultSpeechModel
                SpeechModel.entries.forEachIndexed { i, model ->
                    if (i > 0) Divider()
                    val chosen = model == activeModel
                    val installed = remember(modelsChanged, model) { speechModels.isInstalled(model) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.RadioButton) { vm.updatePrefs { it.copy(speechModel = model.id) } }
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                model.label,
                                color = if (chosen) JournalColors.TextStrong else JournalColors.Text,
                                fontSize = 15.sp,
                                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                "${model.detail} · ${if (installed) "Downloaded" else "${model.sizeMb} MB download"}",
                                color = JournalColors.Muted,
                                fontSize = 12.sp,
                            )
                        }
                        if (chosen) LineIcon(Glyph.Check, JournalColors.Accent, size = 18.dp, strokeWidth = 2.2f)
                        if (installed) {
                            IconAction(Glyph.Trash, "Delete the ${model.label} model from this device", JournalColors.Muted, {
                                speechModels.delete(model)
                                modelsChanged++
                            })
                        } else {
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
            }

            // ---------- trash ----------
            Group("Your entries") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(role = Role.Button) { trashOpen = true }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LineIcon(Glyph.Trash, JournalColors.Muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Trash", color = JournalColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Deleted entries, kept for 30 days", color = JournalColors.Muted, fontSize = 12.sp)
                    }
                    Text(if (trash.isEmpty()) "Empty" else "${trash.size}", color = JournalColors.Muted, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    LineIcon(Glyph.Forward, JournalColors.Muted, size = 16.dp)
                }
            }

            // ---------- about ----------
            Group("About") {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Version", color = JournalColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(APP_VERSION, color = JournalColors.Muted, fontSize = 15.sp)
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
                val fill by animateColorAsState(if (isSelected) JournalColors.AccentContainer else Color.Transparent, Motion.standard(), label = "size fill")
                val ink by animateColorAsState(if (isSelected) JournalColors.Accent else JournalColors.Preview, Motion.standard(), label = "size ink")
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(fill)
                        .clickable(role = Role.RadioButton) { onPick(size) }
                        .semantics {
                            contentDescription = size.label
                            selected = isSelected
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Aa",
                        color = ink,
                        fontFamily = font,
                        fontSize = buttonSizes[i].sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        val previewSize by animateFloatAsState(current.sp.toFloat(), Motion.standard(), label = "preview size")
        Text(
            "It started raining halfway down the hill and I didn't even mind.",
            color = JournalColors.Body,
            fontFamily = font,
            fontSize = previewSize.sp,
            lineHeight = (previewSize * 1.55f).sp,
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(Motion.standard())
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
            val fill by animateColorAsState(if (isSelected) JournalColors.AccentContainer else Color.Transparent, Motion.standard(), label = "segment fill")
            val ink by animateColorAsState(if (isSelected) JournalColors.Accent else JournalColors.Preview, Motion.standard(), label = "segment ink")
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(fill)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .semantics { selected = isSelected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = ink,
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
