package dev.sushnt.journal.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPhotoPicker(onPicked: (List<ByteArray>) -> Unit): () -> Unit = {} // iOS: later

@Composable
actual fun rememberCameraCapture(onTaken: (ByteArray) -> Unit): (() -> Unit)? = null // iOS: later
