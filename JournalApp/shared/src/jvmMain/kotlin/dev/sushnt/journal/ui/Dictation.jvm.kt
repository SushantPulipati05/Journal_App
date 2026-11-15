package dev.sushnt.journal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Robot
import java.awt.event.KeyEvent

/**
 * Desktop: Windows has excellent voice typing built in (Win + H). The mic button simply
 * opens it; Windows then types into the text where your cursor is. Click into the text first.
 * Not available on macOS/Linux yet (null: no mic button).
 */
@Composable
actual fun rememberDictation(
    language: String,
    onText: (text: String, final: Boolean) -> Unit,
    onStopped: () -> Unit,
    onError: (String) -> Unit,
): Dictation? {
    val isWindows = remember { System.getProperty("os.name").lowercase().contains("win") }
    if (!isWindows) return null
    return remember {
        object : Dictation {
            override val listening = false
            override val level = 0f
            override val ready = false

            override fun start() {
                runCatching {
                    Robot().apply {
                        keyPress(KeyEvent.VK_WINDOWS)
                        keyPress(KeyEvent.VK_H)
                        keyRelease(KeyEvent.VK_H)
                        keyRelease(KeyEvent.VK_WINDOWS)
                    }
                }.onFailure { onError("Press Win + H to dictate.") }
            }

            override fun stop() {}
        }
    }
}
