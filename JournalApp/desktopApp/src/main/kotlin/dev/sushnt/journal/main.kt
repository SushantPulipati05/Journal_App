package dev.sushnt.journal

import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import dev.sushnt.journal.ui.AppIcon
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sushnt.journal.data.desktopPlatformConfig
import dev.sushnt.journal.di.initKoin
import java.awt.Dimension

fun main() {
    initKoin(desktopPlatformConfig())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Journal",
            icon = rememberVectorPainter(AppIcon), // window + taskbar icon
            // Wide enough for the sidebar layout; shrinking it below ~840 px switches to the phone layout.
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
        ) {
            window.minimumSize = Dimension(420, 560)
            App()
        }
    }
}
