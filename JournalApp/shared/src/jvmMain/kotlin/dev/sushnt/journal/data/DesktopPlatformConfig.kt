package dev.sushnt.journal.data

import java.io.File

/**
 * Where the desktop app keeps its files:
 *  Windows: %APPDATA%\Journal
 *  macOS:   ~/Library/Application Support/Journal
 *  Linux:   ~/.local/share/journal
 */
fun desktopPlatformConfig(): PlatformConfig {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val dir = when {
        os.contains("win") -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "Journal")
        os.contains("mac") -> File(home, "Library/Application Support/Journal")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "journal")
    }
    dir.mkdirs()
    return PlatformConfig(dir.absolutePath)
}
