package dev.sushnt.journal.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.sushnt.journal.data.SpeechModel
import dev.sushnt.journal.data.SpeechModelPaths

private const val RATE = 16_000

/** Phones: "Balanced" by default; old phones can switch to "Fast" in Settings. */
actual val defaultSpeechModel: SpeechModel = SpeechModel.Base

actual fun speechThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

@Composable
actual fun rememberMicPermission(): (onResult: (Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    val holder = remember { arrayOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        holder[0]?.invoke(granted)
        holder[0] = null
    }
    return remember(launcher) {
        { onResult ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                onResult(true)
            } else {
                holder[0] = onResult
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

actual class Microphone actual constructor() {
    private var record: AudioRecord? = null
    private var shorts = ShortArray(0)

    @SuppressLint("MissingPermission") // asked for before dictation starts
    actual fun open(): Boolean {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        } catch (_: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        r.startRecording()
        record = r
        return true
    }

    actual fun read(buffer: FloatArray): Int {
        val r = record ?: return -1
        if (shorts.size < buffer.size) shorts = ShortArray(buffer.size)
        val n = r.read(shorts, 0, buffer.size)
        for (i in 0 until n) buffer[i] = shorts[i] / 32768f
        return n
    }

    actual fun close() {
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}

actual class Transcriber actual constructor(paths: SpeechModelPaths, language: String, threads: Int) {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = paths.vad,
                threshold = 0.5f,
                minSilenceDuration = 0.6f, // a breath isn't the end of a sentence
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 20f, // Whisper handles up to 30 s at a time
            ),
            sampleRate = RATE,
            numThreads = 1,
        ),
    )
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = paths.encoder, decoder = paths.decoder, language = language),
                tokens = paths.tokens,
                numThreads = threads,
            ),
            decodingMethod = "greedy_search",
        ),
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
