package dev.sushnt.journal.ui

import androidx.compose.runtime.Composable
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.sushnt.journal.data.SpeechModel
import dev.sushnt.journal.data.SpeechModelPaths
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

private const val RATE = 16_000

/** Computers are fast enough for the most accurate model. */
actual val defaultSpeechModel: SpeechModel = SpeechModel.Small

actual fun speechThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)

/** Desktop: Windows asks about the microphone itself, the first time it's opened. */
@Composable
actual fun rememberMicPermission(): (onResult: (Boolean) -> Unit) -> Unit = { it(true) }

actual class Microphone actual constructor() {
    private var line: TargetDataLine? = null
    private var bytes = ByteArray(0)

    actual fun open(): Boolean = try {
        val format = AudioFormat(RATE.toFloat(), 16, 1, true, false)
        val l = AudioSystem.getTargetDataLine(format)
        l.open(format, RATE) // ~0.5 s buffer
        l.start()
        line = l
        true
    } catch (_: Exception) {
        false
    }

    actual fun read(buffer: FloatArray): Int {
        val l = line ?: return -1
        val want = buffer.size * 2
        if (bytes.size < want) bytes = ByteArray(want)
        val got = l.read(bytes, 0, want)
        val n = got / 2
        for (i in 0 until n) {
            val s = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
            buffer[i] = s.toShort() / 32768f
        }
        return n
    }

    actual fun close() {
        line?.let {
            runCatching { it.stop() }
            it.close()
        }
        line = null
    }
}

actual class Transcriber actual constructor(paths: SpeechModelPaths, language: String, threads: Int) {
    private val vad = Vad(
        VadModelConfig.builder()
            .setSileroVadModelConfig(
                SileroVadModelConfig.builder()
                    .setModel(paths.vad)
                    .setThreshold(0.5f)
                    .setMinSilenceDuration(0.6f) // a breath isn't the end of a sentence
                    .setMinSpeechDuration(0.25f)
                    .setWindowSize(512)
                    .setMaxSpeechDuration(20f) // Whisper handles up to 30 s at a time
                    .build(),
            )
            .setSampleRate(RATE)
            .setNumThreads(1)
            .build(),
    )
    private val recognizer = OfflineRecognizer(
        OfflineRecognizerConfig.builder()
            .setOfflineModelConfig(
                OfflineModelConfig.builder()
                    .setWhisper(
                        OfflineWhisperModelConfig.builder()
                            .setEncoder(paths.encoder)
                            .setDecoder(paths.decoder)
                            .setLanguage(language)
                            .build(),
                    )
                    .setTokens(paths.tokens)
                    .setNumThreads(threads)
                    .build(),
            )
            .setDecodingMethod("greedy_search")
            .build(),
    )
    private var pending = FloatArray(0)

    actual fun accept(samples: FloatArray) {
        // The voice detector wants exactly 512 samples at a time.
        pending += samples
        var at = 0
        while (pending.size - at >= 512) {
            vad.acceptWaveform(pending.copyOfRange(at, at + 512))
            at += 512
        }
        pending = pending.copyOfRange(at, pending.size)
    }

    actual fun nextSentence(): FloatArray? {
        if (vad.empty()) return null
        val samples = vad.front().samples
        vad.pop()
        return samples
    }

    actual fun flush() {
        pending = FloatArray(0)
        vad.flush()
    }

    actual fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    actual fun release() {
        vad.release()
        recognizer.release()
    }
}
