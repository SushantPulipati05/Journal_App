package dev.sushnt.journal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

@Composable
actual fun rememberPhotoPicker(onPicked: (List<ByteArray>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onPicked)
    return remember {
        {
            val dialog = FileDialog(null as Frame?, "Add photos", FileDialog.LOAD).apply {
                isMultipleMode = true
                file = "*.jpg;*.jpeg;*.png;*.webp;*.gif;*.bmp" // the filter Windows understands
                setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in imageExtensions } // macOS/Linux
            }
            dialog.isVisible = true // waits until the user picks or cancels
            val files: List<File> = dialog.files?.toList().orEmpty()
            if (files.isNotEmpty()) {
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        files.filter { it.isFile && it.length() <= 60L * 1024 * 1024 }.map { it.readBytes() }
                    }
                    callback.value(bytes)
                }
            }
        }
    }
}

/** Desktop: no camera; the Photo button opens the file picker straight away. */
@Composable
actual fun rememberCameraCapture(onTaken: (ByteArray) -> Unit): (() -> Unit)? = null
