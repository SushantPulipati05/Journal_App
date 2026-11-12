@file:OptIn(ExperimentalLayoutApi::class)

package dev.sushnt.journal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import dev.sushnt.journal.ui.Motion.fadeThrough
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sushnt.journal.data.AppPrefs
import dev.sushnt.journal.data.Entry
import dev.sushnt.journal.data.JournalsView
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Journals on a wide window. Not a list: either a gallery of cards (Gallery) or
 * one long diary you scroll through (Timeline). Clicking an entry opens it as a
 * sheet over the page (see EntrySheet).
 */
@Composable
fun DesktopJournals(
    vm: EntriesViewModel,
    prefs: AppPrefs,
    /** The entry open in the sheet, highlighted in the gallery. */
    selectedId: String?,
    onOpen: (Entry) -> Unit,
    onViewChange: (JournalsView) -> Unit,
    /** Goes up by one each time Ctrl+F asks to jump into the search box. */
    focusSearchSignal: Int,
    /** Space kept free at the bottom (for the floating tab bar when the sidebar is closed). */
    bottomPadding: Dp,
) {
    val all by vm.entries.collectAsState()
    val sections by vm.sections.collectAsState()
    val query by vm.query.collectAsState()
    val streak by vm.streak.collectAsState()
    val pendingDelete by vm.pendingDelete.collectAsState()
    val loading by vm.loading.collectAsState()
    val shown = remember(sections) { sections.flatMap { it.entries } } // newest first, search applied
    val snackbar = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(focusSearchSignal) {
        if (focusSearchSignal > 0) runCatching { searchFocus.requestFocus() }
    }
    LaunchedEffect(pendingDelete?.id) {
        if (pendingDelete == null) return@LaunchedEffect
        val result = snackbar.showSnackbar("Entry deleted", actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 28.dp)) {
            Header(
                loading = loading,
                total = all.size,
                streak = if (prefs.showStreak) streak else 0,
                query = query,
                onQuery = vm::setQuery,
                searchFocus = searchFocus,
                view = prefs.journalsView,
                onViewChange = onViewChange,
            )
            Spacer(Modifier.height(20.dp))

            val body = when {
                loading -> Body.Loading
                shown.isEmpty() -> Body.Empty
                else -> Body.Entries
            }
            // Skeleton -> real entries is a soft cross-fade, not a jump.
            AnimatedContent(
                targetState = body,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { fadeThrough() },
                label = "journals body",
            ) { shownBody ->
                when (shownBody) {
                    Body.Loading -> when (prefs.journalsView) {
                        JournalsView.Gallery -> GallerySkeleton()
                        JournalsView.Timeline -> TimelineSkeleton()
                    }
                    Body.Empty -> Text(
                        if (all.isEmpty()) "Nothing here yet.\nWhat you write on the Write page shows up here."
                        else "No entries match \"$query\".",
                        color = JournalColors.Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                    )
                    // Switching views cross-fades; the choice is remembered on this device.
                    Body.Entries -> AnimatedContent(
                        targetState = prefs.journalsView,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = { fadeThrough() },
                        label = "journals view",
                    ) { view ->
                        when (view) {
                            JournalsView.Gallery -> Gallery(shown, prefs, selectedId, onOpen, bottomPadding)
                            JournalsView.Timeline -> Timeline(shown, all, prefs, onOpen, bottomPadding)
                        }
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding.coerceAtLeast(24.dp))) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = JournalColors.Raised,
                contentColor = JournalColors.Text,
                actionColor = JournalColors.Accent,
            )
        }
    }
}

// ---------------------------------------------------------------- header

private enum class Body { Loading, Empty, Entries }

@Composable
private fun Header(
    loading: Boolean,
    total: Int,
    streak: Int,
    query: String,
    onQuery: (String) -> Unit,
    searchFocus: FocusRequester,
    view: JournalsView,
    onViewChange: (JournalsView) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f).widthIn(min = 200.dp)) {
            Text("Journals", color = JournalColors.TextStrong, fontFamily = writingFont(true), fontSize = 36.sp, fontWeight = FontWeight.Medium)
            if (loading) {
                SkeletonBlock(Modifier.padding(top = 6.dp), width = 150.dp, height = 11.dp)
            } else {
                Text(
                    buildString {
                        append(if (total == 1) "1 entry" else "$total entries")
                        if (streak > 1) append(" · $streak-day streak")
                    },
                    color = JournalColors.Muted,
                    fontSize = 13.sp,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchBox(query, onQuery, searchFocus)
            Spacer(Modifier.width(10.dp))
            ViewSwitch(view, onViewChange)
        }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, focus: FocusRequester) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .width(240.dp)
            .height(40.dp)
            .clip(shape)
            .background(JournalColors.Card)
            .border(1.dp, JournalColors.Border, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(Glyph.Search, JournalColors.Muted, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search entries", color = Color(0xFF7C859B), fontSize = 13.sp)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = JournalColors.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(JournalColors.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search entries" },
            )
        }
        if (query.isEmpty()) {
            Text("Ctrl F", color = JournalColors.Muted, fontSize = 11.sp)
        } else {
            Text(
                "Clear",
                color = JournalColors.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { onChange("") }.padding(4.dp),
            )
        }
    }
}

@Composable
private fun ViewSwitch(view: JournalsView, onChange: (JournalsView) -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(JournalColors.Card)
            .border(1.dp, JournalColors.Border, CircleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ViewSwitchButton("Gallery", Glyph.Grid, view == JournalsView.Gallery) { onChange(JournalsView.Gallery) }
        ViewSwitchButton("Timeline", Glyph.Stream, view == JournalsView.Timeline) { onChange(JournalsView.Timeline) }
    }
}

@Composable
private fun ViewSwitchButton(label: String, glyph: Glyph, isSelected: Boolean, onClick: () -> Unit) {
    val ink by animateColorAsState(if (isSelected) JournalColors.Accent else Color(0xFF9AA3B8), Motion.standard(), label = "switch ink")
    val fill by animateColorAsState(if (isSelected) JournalColors.AccentContainer else Color.Transparent, Motion.standard(), label = "switch fill")
    Row(
        Modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { selected = isSelected }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(glyph, ink, size = 15.dp, strokeWidth = 1.9f)
        Spacer(Modifier.width(7.dp))
        Text(label, color = ink, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

// ---------------------------------------------------------------- gallery

/** Cards in a masonry grid, grouped by month. Short entries become quotes. */
@Composable
private fun Gallery(entries: List<Entry>, prefs: AppPrefs, selectedId: String?, onOpen: (Entry) -> Unit, bottomPadding: Dp) {
    val months = remember(entries) { entries.groupBy { monthOf(it) } } // keeps newest-first order
    val now = remember { localDate(nowMillis()) }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(230.dp),
        state = rememberLazyStaggeredGridState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding + 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalItemSpacing = 14.dp,
    ) {
        months.forEach { (month, monthEntries) ->
            item(key = "month-$month", span = StaggeredGridItemSpan.FullLine) {
                MonthHeader(monthLabel(month, now), monthEntries.size, Modifier.animateItem())
            }
            items(monthEntries, key = { it.id }) { entry ->
                GalleryCard(entry, prefs, selected = entry.id == selectedId, onClick = { onOpen(entry) }, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun MonthHeader(label: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = JournalColors.TextStrong, fontFamily = writingFont(true), fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF1A2132)))
        Spacer(Modifier.width(14.dp))
        Text(if (count == 1) "1 entry" else "$count entries", color = JournalColors.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun GalleryCard(entry: Entry, prefs: AppPrefs, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val lift by animateDpAsState(if (hovered) (-2).dp else 0.dp, Motion.gentleSpring(), label = "card lift")
    val border by animateColorAsState(
        when {
            selected -> JournalColors.Accent
            hovered -> Color(0xFF343D57)
            else -> JournalColors.Border
        },
        Motion.standard(),
        label = "card border",
    )
    val shape = RoundedCornerShape(16.dp)
    val text = remember(entry.title, entry.body) { joinEntryText(entry.title, entry.body).trim() }
    val words = remember(text) { wordCount(text) }
    val asQuote = words in 1..25 // a one-liner reads better as a quote than as a title

    Column(
        modifier
            .fillMaxWidth()
            .offset(y = lift)
            .pressScale(source)
            .clip(shape)
            .background(if (selected) Color(0xFF171B33) else JournalColors.Card)
            .border(1.dp, border, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                shortDayLabel(entry.createdAt).uppercase(),
                color = JournalColors.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.7.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(timeLabel(entry.createdAt), color = JournalColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        if (asQuote) {
            Text(
                "“$text”",
                color = JournalColors.Text,
                fontFamily = writingFont(prefs.serifFont),
                fontStyle = FontStyle.Italic,
                fontSize = 19.sp,
                lineHeight = 27.sp,
            )
        } else {
            if (entry.title.isNotBlank()) {
                Text(
                    entry.title,
                    color = JournalColors.TextStrong,
                    fontFamily = writingFont(prefs.serifFont),
                    fontSize = 20.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.body.isNotBlank()) {
                Text(
                    entry.body.trim(),
                    color = JournalColors.Preview,
                    fontFamily = writingFont(prefs.serifFont),
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    maxLines = 7, // long entries make taller cards, like a real gallery
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (words == 1) "1 word" else "$words words", color = Color(0xFF6B7389), fontSize = 11.sp)
            if (entry.dirty) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(6.dp).clip(CircleShape).background(JournalColors.Warning))
                Spacer(Modifier.width(5.dp))
                Text("Not synced", color = JournalColors.Muted, fontSize = 11.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- timeline

/** One long diary: a big date on the left, the full entries on the right, a month calendar to jump around. */
@Composable
private fun Timeline(entries: List<Entry>, all: List<Entry>, prefs: AppPrefs, onOpen: (Entry) -> Unit, bottomPadding: Dp) {
    val days = remember(entries) { entries.groupBy { localDate(it.createdAt) }.toList() } // newest day first
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showCalendar = maxWidth >= 860.dp
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = bottomPadding + 8.dp),
            ) {
                items(days, key = { it.first.toString() }) { (day, dayEntries) ->
                    TimelineDay(day, dayEntries, prefs, onOpen, Modifier.animateItem())
                }
            }
            if (showCalendar) {
                Spacer(Modifier.width(32.dp))
                MonthCalendar(
                    entries = all,
                    onJump = { day ->
                        val index = days.indexOfFirst { it.first == day }
                        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                    },
                    modifier = Modifier.width(240.dp),
                )
            }
        }
    }
}

@Composable
private fun TimelineDay(day: LocalDate, entries: List<Entry>, prefs: AppPrefs, onOpen: (Entry) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A2132)))
        Row(Modifier.fillMaxWidth().padding(vertical = 26.dp)) {
            Column(Modifier.width(84.dp), horizontalAlignment = Alignment.End) {
                Text(day.day.toString(), color = JournalColors.TextStrong, fontFamily = writingFont(true), fontSize = 44.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${day.dayOfWeek.name.take(3)} · ${day.month.name.take(3)}",
                    color = JournalColors.Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                )
            }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f).widthIn(max = 660.dp)) {
                entries.forEachIndexed { i, entry ->
                    if (i > 0) Box(Modifier.padding(top = 6.dp, bottom = 20.dp).width(40.dp).height(1.dp).background(Color(0xFF262E45)))
                    TimelineEntry(entry, prefs, onClick = { onOpen(entry) })
                }
            }
        }
    }
}

@Composable
private fun TimelineEntry(entry: Entry, prefs: AppPrefs, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val size = prefs.textSize.sp
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(timeLabel(entry.createdAt), color = JournalColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            AnimatedVisibility(hovered, enter = fadeIn(Motion.quick()), exit = fadeOut(Motion.quick())) {
                Text("Open ↗", color = JournalColors.Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (entry.title.isNotBlank()) {
            Text(
                entry.title,
                color = JournalColors.TextStrong,
                fontFamily = writingFont(prefs.serifFont),
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (entry.body.isNotBlank()) {
            Text(
                entry.body.trim(),
                color = JournalColors.Body,
                fontFamily = writingFont(prefs.serifFont),
                fontSize = (size - 1).sp,
                lineHeight = ((size - 1) * 1.7f).sp,
            )
        }
    }
}

/** A small month grid: the days you wrote are filled in; click one to jump to it. */
@Composable
private fun MonthCalendar(entries: List<Entry>, onJump: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val today = remember { localDate(nowMillis()) }
    var month by remember { mutableStateOf(LocalDate(today.year, today.month.ordinal + 1, 1)) }
    val next = month.plus(1, DateTimeUnit.MONTH)
    val written = remember(entries) { entries.map { localDate(it.createdAt) }.toSet() }
    val monthWords = remember(entries, month) {
        entries.filter { localDate(it.createdAt).let { d -> d >= month && d < next } }
            .sumOf { wordCount(joinEntryText(it.title, it.body)) }
    }
    val daysInMonth = month.daysUntil(next)
    val lead = month.dayOfWeek.ordinal // Monday = 0
    val weeks = (lead + daysInMonth + 6) / 7
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier
            .clip(shape)
            .background(JournalColors.Card)
            .border(1.dp, JournalColors.Border, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${month.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${month.year}",
                color = JournalColors.TextStrong,
                fontFamily = writingFont(true),
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            SmallArrow(Glyph.Back, "Previous month") { month = month.minus(1, DateTimeUnit.MONTH) }
            Spacer(Modifier.width(4.dp))
            SmallArrow(Glyph.Forward, "Next month") { month = next }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, color = Color(0xFF6B7389), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (w in 0 until weeks) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (col in 0 until 7) {
                        val n = w * 7 + col - lead + 1
                        Box(Modifier.weight(1f).height(30.dp)) {
                            if (n in 1..daysInMonth) {
                                val day = LocalDate(month.year, month.month.ordinal + 1, n)
                                DayCell(day, isToday = day == today, wrote = day in written, future = day > today) { onJump(day) }
                            }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(JournalColors.AccentContainer))
            Spacer(Modifier.width(8.dp))
            Text("Days you wrote · click to jump", color = JournalColors.Muted, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(JournalColors.Border))
        Column {
            Text("${formatThousands(monthWords)} words", color = JournalColors.TextStrong, fontFamily = writingFont(true), fontSize = 22.sp)
            Text("written this month", color = JournalColors.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DayCell(day: LocalDate, isToday: Boolean, wrote: Boolean, future: Boolean, onClick: () -> Unit) {
    val fill = when {
        isToday -> JournalColors.Accent
        wrote -> JournalColors.AccentContainer
        else -> Color.Transparent
    }
    val ink = when {
        isToday -> JournalColors.Background
        wrote -> JournalColors.Text
        future -> Color(0xFF3A4258)
        else -> JournalColors.Muted
    }
    var modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(fill)
    if (wrote) modifier = modifier.clickable(role = Role.Button, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(day.day.toString(), color = ink, fontSize = 12.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun SmallArrow(glyph: Glyph, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF262E45), RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { LineIcon(glyph, JournalColors.Muted, size = 14.dp) }
}

// ---------------------------------------------------------------- skeletons

/** Gallery-shaped placeholders: a month header and cards of different heights. */
@Composable
private fun GallerySkeleton() {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(230.dp),
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalItemSpacing = 14.dp,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Row(Modifier.padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(width = 120.dp, height = 20.dp)
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF1A2132)))
            }
        }
        items(listOf(3, 1, 5, 2, 4, 2, 6, 3)) { lines ->
            SkeletonEntryCard(lines = lines, titleFraction = if (lines % 2 == 0) 0.8f else 0.6f)
        }
    }
}

/** Timeline-shaped placeholders: a few days with paragraphs. */
@Composable
private fun TimelineSkeleton() {
    Column(Modifier.fillMaxSize()) {
        SkeletonTimelineDay(paragraphLines = 5)
        SkeletonTimelineDay(paragraphLines = 3)
        SkeletonTimelineDay(paragraphLines = 4)
    }
}

// ---------------------------------------------------------------- small helpers

/** First day of the entry's month; used to group the gallery. */
private fun monthOf(entry: Entry): LocalDate {
    val d = localDate(entry.createdAt)
    return LocalDate(d.year, d.month.ordinal + 1, 1)
}

/** "November", or "November 2025" for other years. */
private fun monthLabel(month: LocalDate, today: LocalDate): String {
    val name = month.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return if (month.year == today.year) name else "$name ${month.year}"
}

/** "Thu 12 Nov" */
private fun shortDayLabel(epochMillis: Long): String {
    val d = localDate(epochMillis)
    val wd = d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    val mo = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    return "$wd ${d.day} $mo"
}

private fun formatThousands(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
