package dev.sushnt.journal

import androidx.compose.foundation.layout.Box
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.sushnt.journal.ui.Motion
import dev.sushnt.journal.ui.Motion.backward
import dev.sushnt.journal.ui.Motion.fadeThrough
import dev.sushnt.journal.ui.Motion.forward
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import dev.sushnt.journal.data.AppPrefs
import dev.sushnt.journal.data.Entry
import dev.sushnt.journal.ui.SettingsViewModel
import dev.sushnt.journal.ui.ClosedSidebarBar
import dev.sushnt.journal.ui.Sidebar
import dev.sushnt.journal.ui.topGlow
import dev.sushnt.journal.ui.SidebarItem
import dev.sushnt.journal.ui.SyncLine
import dev.sushnt.journal.ui.relativeLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.sushnt.journal.data.BackgroundSync
import dev.sushnt.journal.data.PrefsStore
import dev.sushnt.journal.data.SessionManager
import dev.sushnt.journal.data.SyncController
import dev.sushnt.journal.ui.AuthScreen
import dev.sushnt.journal.ui.EntriesViewModel
import dev.sushnt.journal.ui.EntryScreen
import dev.sushnt.journal.ui.HomeTab
import dev.sushnt.journal.ui.JournalColors
import dev.sushnt.journal.ui.JournalTheme
import dev.sushnt.journal.ui.JournalsScreen
import dev.sushnt.journal.ui.SettingsScreen
import dev.sushnt.journal.ui.TabBar
import dev.sushnt.journal.ui.WriteScreen
import dev.sushnt.journal.ui.WriteViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration.Companion.minutes

/** The whole app. Every platform shows this. initKoin() must have run first. */
@Composable
fun App() {
    JournalTheme {
        Surface(Modifier.fillMaxSize(), color = JournalColors.Background) {
            val session = koinInject<SessionManager>()
            val signedIn by session.signedIn.collectAsState(initial = null)
            val write: WriteViewModel = koinViewModel()

            // The Write page lives as long as the app; never carry it into the next account.
            LaunchedEffect(signedIn) { if (signedIn == false) write.reset() }

            when (signedIn) {
                true -> Home(write)
                false -> AuthScreen()
                null -> {} // first instant while reading the saved login
            }
        }
    }
}

@Composable
private fun Home(write: WriteViewModel, journals: EntriesViewModel = koinViewModel()) {
    val prefsStore = koinInject<PrefsStore>()
    val prefs by prefsStore.prefs.collectAsState()
    val syncController = koinInject<SyncController>()
    val backgroundSync = koinInject<BackgroundSync>()
    val entries by journals.entries.collectAsState()

    // rememberSaveable: survives an Android phone rotating.
    var tab by rememberSaveable { mutableStateOf(HomeTab.Write) }
    var readingId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        backgroundSync.schedulePeriodic() // hourly sync while the app is closed (Android)
        while (true) {                    // and every 5 minutes while it's open
            syncController.syncNow()
            delay(5.minutes)
        }
    }
    // Coming back to the app: fetch what other devices changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { syncController.syncNow() }
    // Leaving the app: save what's being typed, and make sure it reaches the server.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        write.flush()
        journals.commitPendingDelete()
        backgroundSync.syncSoon()
    }

    val reading = readingId?.let { id -> entries.find { it.id == id } }
    // Kept here so the Journals list stays scrolled where you left it after reading an entry.
    val listState = rememberLazyListState()
    val goTo: (HomeTab) -> Unit = { next ->
        if (tab == HomeTab.Write && next != HomeTab.Write) write.flush()
        tab = next
        settingsOpen = false
    }

    BoxWithConstraints(Modifier.fillMaxSize().topGlow().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // The layout follows the window size, not the platform: a wide window (desktop,
        // tablet, unfolded foldable) gets the sidebar; a narrow one gets the tab bar.
        if (maxWidth >= 840.dp) {
            WideHome(
                write = write,
                journals = journals,
                prefs = prefs,
                tab = tab,
                settingsOpen = settingsOpen,
                reading = reading,
                listState = listState,
                onGoTo = goTo,
                onOpenSettings = {
                    write.flush()
                    settingsOpen = true
                },
                onCloseSettings = { settingsOpen = false },
                onRead = { readingId = it },
                onToggleSidebar = { collapsed ->
                    write.flush()
                    prefsUpdate(prefsStore) { it.copy(sidebarCollapsed = collapsed) }
                },
            )
        } else {
            CompactHome(
                write = write,
                journals = journals,
                prefs = prefs,
                tab = tab,
                settingsOpen = settingsOpen,
                reading = reading,
                listState = listState,
                onGoTo = goTo,
                onOpenSettings = {
                    write.flush()
                    settingsOpen = true
                },
                onCloseSettings = { settingsOpen = false },
                onRead = { readingId = it },
            )
        }
    }
}

/** Saves a preference without blocking the UI. */
private fun prefsUpdate(store: PrefsStore, change: (AppPrefs) -> AppPrefs) {
    prefsScope.launch { store.update(change) }
}

private val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Which full screen the phone layout shows. Depth decides the direction of the slide. */
private sealed interface PhoneScreen {
    val depth: Int
    data object Write : PhoneScreen { override val depth = 0 }
    data object Journals : PhoneScreen { override val depth = 0 }
    data class Reading(val id: String) : PhoneScreen { override val depth = 1 }
    data object Settings : PhoneScreen { override val depth = 1 }
}

/** Phones: one screen at a time, tab bar at the bottom. Every change of screen is animated. */
@Composable
private fun BoxScope.CompactHome(
    write: WriteViewModel,
    journals: EntriesViewModel,
    prefs: AppPrefs,
    tab: HomeTab,
    settingsOpen: Boolean,
    reading: Entry?,
    listState: LazyListState,
    onGoTo: (HomeTab) -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onRead: (String?) -> Unit,
) {
    // Keyboard up = focus mode: the tab bar steps aside.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val entries by journals.entries.collectAsState()

    val screen: PhoneScreen = when {
        settingsOpen -> PhoneScreen.Settings
        reading != null -> PhoneScreen.Reading(reading.id)
        tab == HomeTab.Journals -> PhoneScreen.Journals
        else -> PhoneScreen.Write
    }

    AnimatedContent(
        targetState = screen,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            when {
                targetState.depth > initialState.depth -> forward()
                targetState.depth < initialState.depth -> backward()
                else -> fadeThrough()
            }
        },
        label = "phone screens",
    ) { shown ->
        when (shown) {
            PhoneScreen.Settings -> SettingsScreen(onClose = onCloseSettings)

            is PhoneScreen.Reading -> {
                // While sliding away after a delete, the entry may already be gone: show nothing.
                val entry = entries.find { it.id == shown.id }
                if (entry != null) {
                    key(entry.id) {
                        // key(): each entry gets its own editing state, so switching entries can
                        // never save one entry's text into another.
                        EntryScreen(
                            entry = entry,
                            prefs = prefs,
                            onBack = { onRead(null) },
                            onSaveText = { text -> journals.saveEdit(entry.id, text) },
                            onDelete = {
                                journals.deleteWithUndo(entry)
                                onRead(null)
                            },
                        )
                    }
                }
            }

            PhoneScreen.Write -> WriteScreen(
                vm = write,
                prefs = prefs,
                keyboardOpen = keyboardOpen,
                onSettings = onOpenSettings,
            )

            PhoneScreen.Journals -> JournalsScreen(
                vm = journals,
                prefs = prefs,
                onOpen = { onRead(it.id) },
                onSettings = onOpenSettings,
                listState = listState,
            )
        }
    }

    // The tab bar slides down out of the way while typing or inside an entry/Settings.
    AnimatedVisibility(
        visible = !keyboardOpen && screen.depth == 0,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
        enter = slideInVertically(Motion.standard()) { it * 2 } + fadeIn(Motion.standard()),
        exit = slideOutVertically(Motion.quick()) { it * 2 } + fadeOut(Motion.quick()),
    ) {
        TabBar(current = tab, onSelect = onGoTo)
    }
}

/** Desktop and tablets: sidebar on the left; Journals shows the list and the entry side by side. */
@Composable
private fun WideHome(
    write: WriteViewModel,
    journals: EntriesViewModel,
    prefs: AppPrefs,
    tab: HomeTab,
    settingsOpen: Boolean,
    reading: Entry?,
    listState: LazyListState,
    onGoTo: (HomeTab) -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onRead: (String?) -> Unit,
    onToggleSidebar: (Boolean) -> Unit,
    settings: SettingsViewModel = koinViewModel(),
) {
    val syncController = koinInject<SyncController>()
    val syncing by syncController.syncing.collectAsState()
    val syncMessage by syncController.message.collectAsState()
    val lastSyncedAt by syncController.lastSyncedAt.collectAsState()
    val entries by journals.entries.collectAsState()
    val streak by journals.streak.collectAsState()
    val account by settings.state.collectAsState()
    var focusSearch by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { settings.load() } // the name shown in the sidebar

    val current = when {
        settingsOpen -> SidebarItem.Settings
        tab == HomeTab.Journals -> SidebarItem.Journals
        else -> SidebarItem.Write
    }
    val syncLine = when {
        syncing -> SyncLine("Syncing…", healthy = true)
        syncMessage != null -> SyncLine("Not synced: offline or server issue", healthy = false)
        lastSyncedAt != null -> SyncLine("Synced · ${relativeLabel(lastSyncedAt!!)}", healthy = true)
        else -> SyncLine("Not synced yet", healthy = true)
    }

    Row(
        Modifier
            .fillMaxSize()
            // Keyboard shortcuts: Ctrl+N new page, Ctrl+F search, Ctrl+\ fold the sidebar.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed)) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.N -> {
                        write.newEntry()
                        onGoTo(HomeTab.Write)
                        true
                    }
                    Key.F -> {
                        onGoTo(HomeTab.Journals)
                        focusSearch++
                        true
                    }
                    Key.Backslash -> {
                        onToggleSidebar(!prefs.sidebarCollapsed)
                        true
                    }
                    else -> false
                }
            },
    ) {
        val userName = account.name ?: account.email
        val sidebarOpen = !prefs.sidebarCollapsed

        // Closing the sidebar hides it completely: the page gets the whole window.
        AnimatedVisibility(
            visible = sidebarOpen,
            enter = expandHorizontally(Motion.standard(), expandFrom = Alignment.Start) + fadeIn(Motion.standard()),
            exit = shrinkHorizontally(Motion.standard(), shrinkTowards = Alignment.Start) + fadeOut(Motion.quick()),
        ) {
            Row(Modifier.fillMaxHeight()) {
                Sidebar(
                    current = current,
                    entryCount = entries.size,
                    streak = streak,
                    showStreak = prefs.showStreak,
                    sync = syncLine,
                    userName = userName,
                    onClose = { onToggleSidebar(true) },
                    onSelect = { item ->
                        when (item) {
                            SidebarItem.Write -> onGoTo(HomeTab.Write)
                            SidebarItem.Journals -> onGoTo(HomeTab.Journals)
                            SidebarItem.Settings -> onOpenSettings()
                        }
                    },
                )
                VerticalLine()
            }
        }

        Box(Modifier.weight(1f).fillMaxHeight()) {
        Column(Modifier.fillMaxSize()) {
        // With the sidebar closed: just a slim bar (open-sidebar button, avatar) at the top...
        AnimatedVisibility(
            visible = !sidebarOpen,
            enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
            exit = shrinkVertically(Motion.standard()) + fadeOut(Motion.quick()),
        ) {
            ClosedSidebarBar(userName, onOpenSidebar = { onToggleSidebar(false) }, onSettings = onOpenSettings)
        }

        AnimatedContent(
            targetState = current,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { fadeThrough() },
            label = "wide sections",
        ) { section ->
            when (section) {
                SidebarItem.Settings -> Centered(640.dp) {
                    SettingsScreen(onClose = onCloseSettings, showBack = false, vm = settings)
                }

                SidebarItem.Write -> Centered(720.dp) {
                    WriteScreen(
                        vm = write,
                        prefs = prefs,
                        keyboardOpen = false,
                        onSettings = onOpenSettings,
                        compact = false,
                        tabBarVisible = !sidebarOpen,
                    )
                }

                SidebarItem.Journals -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(380.dp).fillMaxHeight()) {
                        JournalsScreen(
                            vm = journals,
                            prefs = prefs,
                            onOpen = { onRead(it.id) },
                            onSettings = onOpenSettings,
                            compact = false,
                            selectedId = reading?.id,
                            focusSearchSignal = focusSearch,
                            listState = listState,
                            bottomPadding = if (sidebarOpen) null else 110.dp,
                        )
                    }
                    VerticalLine()
                    // Picking another entry cross-fades the reading pane.
                    AnimatedContent(
                        targetState = reading?.id,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        transitionSpec = { fadeThrough() },
                        label = "entry pane",
                    ) { id ->
                        val entry = id?.let { wanted -> entries.find { it.id == wanted } }
                        Box(Modifier.fillMaxSize()) {
                        if (entry != null) {
                            Centered(760.dp) {
                                key(entry.id) { EntryScreen(
                                    entry = entry,
                                    prefs = prefs,
                                    onBack = { onRead(null) },
                                    onSaveText = { text -> journals.saveEdit(entry.id, text) },
                                    onDelete = {
                                        journals.deleteWithUndo(entry)
                                        onRead(null)
                                    },
                                    showBack = false,
                                ) }
                            }
                        } else {
                            Text(
                                if (entries.isEmpty()) "Your entries will appear here." else "Pick an entry to read it.",
                                color = JournalColors.Muted,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        }
                    }
                }
            }
        }
        }
        // ...and the floating Write/Journals pill at the bottom, like on the phone.
        AnimatedVisibility(
            visible = !sidebarOpen && current != SidebarItem.Settings,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            enter = slideInVertically(Motion.standard()) { it * 2 } + fadeIn(Motion.standard()),
            exit = slideOutVertically(Motion.quick()) { it * 2 } + fadeOut(Motion.quick()),
        ) {
            TabBar(current = if (current == SidebarItem.Journals) HomeTab.Journals else HomeTab.Write, onSelect = onGoTo)
        }
        }
    }
}

/** Keeps text at a comfortable reading width on big screens. */
@Composable
private fun Centered(maxWidth: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxSize().padding(top = 16.dp)) { content() }
    }
}

@Composable
private fun VerticalLine() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(JournalColors.Border))
}
