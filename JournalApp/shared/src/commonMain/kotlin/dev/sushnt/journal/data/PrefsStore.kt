package dev.sushnt.journal.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The five text sizes in Settings. [sp] is the size of the writing/reading text. */
enum class EntryTextSize(val label: String, val sp: Int) {
    ExtraSmall("Extra small", 16),
    Small("Small", 17),
    Default("Default", 19),
    Large("Large", 22),
    ExtraLarge("Extra large", 25),
}

/** How the desktop Journals page lays out entries. */
enum class JournalsView { Gallery, Timeline }

/** Settings that belong to this device, not to the account. */
data class AppPrefs(
    val textSize: EntryTextSize = EntryTextSize.Default,
    val serifFont: Boolean = true,
    val showStreak: Boolean = true,
    /** Reopening the app within 30 minutes brings back the entry you were writing. */
    val continueLastEntry: Boolean = true,
    /** Desktop/tablet: the sidebar is folded down to icons. */
    val sidebarCollapsed: Boolean = false,
    /** Desktop/tablet Journals page: cards or one long diary stream. */
    val journalsView: JournalsView = JournalsView.Gallery,
)

/** Stores [AppPrefs] in a tiny SQLite file next to the journal. Survives sign-out. */
class PrefsStore(
    private val connection: SQLiteConnection,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lock = Mutex()
    private val _prefs: MutableStateFlow<AppPrefs>
    val prefs: StateFlow<AppPrefs> get() = _prefs

    init {
        connection.exec("CREATE TABLE IF NOT EXISTS kv (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
        _prefs = MutableStateFlow(readAll())
    }

    suspend fun update(change: (AppPrefs) -> AppPrefs) = lock.withLock {
        val next = change(_prefs.value)
        _prefs.value = next // the screen updates at once; the disk write follows
        withContext(dispatcher) {
            write("text_size", next.textSize.name)
            write("serif_font", next.serifFont.toString())
            write("show_streak", next.showStreak.toString())
            write("continue_last", next.continueLastEntry.toString())
            write("sidebar_collapsed", next.sidebarCollapsed.toString())
            write("journals_view", next.journalsView.name)
        }
    }

    private fun readAll(): AppPrefs {
        val map = connection.queryRows("SELECT key, value FROM kv") { it.getText(0) to it.getText(1) }.toMap()
        val defaults = AppPrefs()
        return AppPrefs(
            textSize = EntryTextSize.entries.firstOrNull { it.name == map["text_size"] } ?: defaults.textSize,
            serifFont = map["serif_font"]?.toBooleanStrictOrNull() ?: defaults.serifFont,
            showStreak = map["show_streak"]?.toBooleanStrictOrNull() ?: defaults.showStreak,
            continueLastEntry = map["continue_last"]?.toBooleanStrictOrNull() ?: defaults.continueLastEntry,
            sidebarCollapsed = map["sidebar_collapsed"]?.toBooleanStrictOrNull() ?: defaults.sidebarCollapsed,
            journalsView = JournalsView.entries.firstOrNull { it.name == map["journals_view"] } ?: defaults.journalsView,
        )
    }

    private fun write(key: String, value: String) {
        connection.exec("INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)") {
            it.bindText(1, key)
            it.bindText(2, value)
        }
    }

    companion object {
        fun open(driver: SQLiteDriver, path: String) = PrefsStore(driver.open(path))
    }
}
