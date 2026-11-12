package dev.sushnt.journal.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPhotoPicker(onPicked: (List<ByteArray>) -> Unit): () -> Unit = {} // iOS: later
