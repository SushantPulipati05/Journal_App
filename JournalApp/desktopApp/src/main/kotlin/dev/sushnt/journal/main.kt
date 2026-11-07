package dev.sushnt.journal

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.sushnt.journal.data.desktopPlatformConfig
import dev.sushnt.journal.di.initKoin

fun main() {
    initKoin(desktopPlatformConfig())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Journal",
        ) {
            App()
        }
    }
}
