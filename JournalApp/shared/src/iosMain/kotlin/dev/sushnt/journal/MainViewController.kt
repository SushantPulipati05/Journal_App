package dev.sushnt.journal

import androidx.compose.ui.window.ComposeUIViewController
import dev.sushnt.journal.data.iosPlatformConfig
import dev.sushnt.journal.di.initKoin
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin(iosPlatformConfig())
    return ComposeUIViewController { App() }
}
