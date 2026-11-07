package dev.sushnt.journal.data

/** What each platform must tell the shared code at startup. */
class PlatformConfig(
    /** A private folder this app may write files in. Must already exist. */
    val dataDir: String,
)
