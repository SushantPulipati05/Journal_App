package dev.sushnt.journal.data

/** What each platform must tell the shared code at startup. */
class PlatformConfig(
    /** A private folder this app may write files in. Must already exist. */
    val dataDir: String,
    /** Syncing while the app is closed. Only Android has it so far. */
    val backgroundSync: BackgroundSync = NoBackgroundSync,
)

/** Lets the OS run a sync for us when the app isn't open. */
interface BackgroundSync {
    /** A regular sync (about hourly) whenever there is internet. Safe to call repeatedly. */
    fun schedulePeriodic()

    /** One sync as soon as there is internet, e.g. right after the app goes to the background. */
    fun syncSoon()

    /** Stop everything (after sign-out). */
    fun cancel()
}

object NoBackgroundSync : BackgroundSync {
    override fun schedulePeriodic() {}
    override fun syncSoon() {}
    override fun cancel() {}
}
