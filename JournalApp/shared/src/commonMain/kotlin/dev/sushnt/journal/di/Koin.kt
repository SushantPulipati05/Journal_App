package dev.sushnt.journal.di

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.sushnt.journal.ApiConfig
import dev.sushnt.journal.api.JournalApi
import dev.sushnt.journal.api.TokenStore
import dev.sushnt.journal.data.EntryStore
import dev.sushnt.journal.data.PlatformConfig
import dev.sushnt.journal.data.SessionManager
import dev.sushnt.journal.data.SqliteTokenStore
import dev.sushnt.journal.data.SyncEngine
import dev.sushnt.journal.ui.AuthViewModel
import dev.sushnt.journal.ui.EntriesViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

private var started = false

/**
 * Builds every shared object once, at app start. Each platform calls this from its
 * own entry point (Android: JournalApplication, desktop: main, iOS: MainViewController).
 */
fun initKoin(config: PlatformConfig) {
    if (started) return
    started = true
    startKoin { modules(appModule(config)) }
}

private fun appModule(config: PlatformConfig) = module {
    single { SqliteTokenStore.open(BundledSQLiteDriver(), "${config.dataDir}/session.db") }
    single<TokenStore> { get<SqliteTokenStore>() }
    single { EntryStore.open(BundledSQLiteDriver(), "${config.dataDir}/journal.db") }
    single { JournalApi(ApiConfig.BASE_URL, get()) }
    single { SyncEngine(get(), get()) }
    single { SessionManager(get(), get(), get(), get()) }

    viewModel { AuthViewModel(get()) }
    viewModel { EntriesViewModel(get(), get(), get()) }
}
