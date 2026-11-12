package dev.sushnt.journal.ui

import androidx.compose.runtime.Composable

/**
 * The system's photo chooser. Returns a function that opens it; [onPicked] receives
 * the raw bytes of every image chosen (nothing if cancelled).
 * Desktop: a file dialog. Android: the system photo picker (no permission needed).
 */
@Composable
expect fun rememberPhotoPicker(onPicked: (List<ByteArray>) -> Unit): () -> Unit
