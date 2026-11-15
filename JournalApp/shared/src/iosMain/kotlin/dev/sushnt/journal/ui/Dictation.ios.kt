package dev.sushnt.journal.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberDictation(
    language: String,
    onText: (text: String, final: Boolean) -> Unit,
    onStopped: () -> Unit,
    onError: (String) -> Unit,
): Dictation? = null // iOS: later
