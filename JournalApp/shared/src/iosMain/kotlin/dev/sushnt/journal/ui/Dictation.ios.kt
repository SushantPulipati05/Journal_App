package dev.sushnt.journal.ui

import androidx.compose.runtime.Composable
import dev.sushnt.journal.data.SpeechModel
import dev.sushnt.journal.data.SpeechModelPaths

// iOS: dictation comes later. These stubs keep the shared code compiling.

actual val defaultSpeechModel: SpeechModel = SpeechModel.Base

actual fun speechThreads(): Int = 2

@Composable
actual fun rememberMicPermission(): (onResult: (Boolean) -> Unit) -> Unit = { it(false) }

actual class Microphone actual constructor() {
    actual fun open(): Boolean = false
    actual fun read(buffer: FloatArray): Int = -1
    actual fun close() {}
}

actual class Transcriber actual constructor(paths: SpeechModelPaths, language: String, threads: Int) {
    init {
        throw UnsupportedOperationException("Dictation isn't available on iOS yet")
    }

    actual fun accept(samples: FloatArray) {}
    actual fun nextSentence(): FloatArray? = null
    actual fun flush() {}
    actual fun transcribe(samples: FloatArray): String = ""
    actual fun release() {}
}
