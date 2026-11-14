package dev.sushnt.journal.data

// iOS: dictation isn't built yet, so there's nothing to download.
actual fun fileLength(path: String): Long = -1L

actual fun downloadToFile(url: String, path: String, onBytes: (Long) -> Unit) {
    throw UnsupportedOperationException("Dictation isn't available on iOS yet")
}
