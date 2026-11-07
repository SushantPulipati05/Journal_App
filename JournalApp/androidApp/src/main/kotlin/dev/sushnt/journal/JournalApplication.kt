package dev.sushnt.journal

import android.app.Application
import dev.sushnt.journal.data.PlatformConfig
import dev.sushnt.journal.di.initKoin

/** Runs once when the Android app process starts, before any screen. */
class JournalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(PlatformConfig(dataDir = filesDir.absolutePath))
    }
}
