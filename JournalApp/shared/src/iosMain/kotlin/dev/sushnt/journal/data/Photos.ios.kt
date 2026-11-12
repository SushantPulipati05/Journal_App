package dev.sushnt.journal.data

import androidx.compose.ui.graphics.ImageBitmap

// iOS isn't built yet. Photos are skipped there for now; entries still work.
actual fun preparePhoto(bytes: ByteArray): PreparedPhoto? = null
actual fun decodeImage(bytes: ByteArray): ImageBitmap? = null
actual fun writeFileBytes(path: String, bytes: ByteArray) {}
actual fun readFileBytes(path: String): ByteArray? = null
actual fun deleteFileAt(path: String) {}
actual fun makeDirs(path: String) {}
