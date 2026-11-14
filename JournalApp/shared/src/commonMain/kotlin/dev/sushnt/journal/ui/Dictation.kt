package dev.sushnt.journal.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sushnt.journal.data.SpeechModel
import dev.sushnt.journal.data.SpeechModelPaths
import dev.sushnt.journal.data.SpeechModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.concurrent.Volatile
import kotlin.math.sqrt

/*
 * Offline dictation with Whisper (via sherpa-onnx). How it works:
 *  1. The microphone is read in small chunks (16 000 samples a second).
 *  2. A voice detector (Silero VAD) notices when you start and stop a sentence.
 *  3. Each finished sentence is turned into text by Whisper, on the device, and goes
 *     in at the cursor. A small clean-up then removes "um"s and repeated words.
 * Nothing is sent anywhere: it works with no internet once the model is downloaded.
 */

/** What the mic button and the footer need to know. */
interface Dictation {
    /** Busy: loading, listening, or writing out the last sentence. */
    val listening: Boolean

    /** How loud you are right now, 0..1, for the pulsing mic. */
    val level: Float

    /** The mic is open: start talking. (Words said before this are lost.) */
    val ready: Boolean

    /** A short note for the footer ("Downloading… 40%", "Writing…"), or null. */
    val status: String?

    /** Set when the speech model must be downloaded first: ask, then [confirmDownload]. */
    val pendingDownload: SpeechModel?

    fun start()
    fun stop()
    fun confirmDownload()
    fun cancelDownload()
}

// ---- what each platform provides (Dictation.android.kt / .jvm.kt / .ios.kt) ----

/** The microphone: 16 kHz, mono, samples between -1 and 1. */
expect class Microphone() {
    /** false if it can't be opened (no mic, no permission). */
    fun open(): Boolean

    /** Fills [buffer]; returns how many samples were read. Blocks until there are some. */
    fun read(buffer: FloatArray): Int
    fun close()
}

/** The voice detector + Whisper. Loading takes a few seconds; create it off the main thread. */
expect class Transcriber(paths: SpeechModelPaths, language: String, threads: Int) {
    /** Feed microphone audio. */
    fun accept(samples: FloatArray)

    /** A finished sentence (its audio), or null if none is ready yet. */
    fun nextSentence(): FloatArray?

    /** You stopped: whatever was being said counts as a finished sentence. */
    fun flush()

    /** Whisper: audio of one sentence -> text. Slow; call off the main thread. */
    fun transcribe(samples: FloatArray): String
    fun release()
}

/** Asks for microphone permission when needed (Android); calls back with the answer. */
@Composable
expect fun rememberMicPermission(): (onResult: (Boolean) -> Unit) -> Unit

/** The model used when you haven't picked one: smaller on phones, bigger on computers. */
expect val defaultSpeechModel: SpeechModel

/** How many processor cores Whisper may use. */
expect fun speechThreads(): Int

// ---- shared logic ----

/**
 * Offline dictation for one screen. [language]: Whisper language code ("en", "hi", ...)
 * or "" to let Whisper detect it. [onText] gets each finished, cleaned-up sentence.
 */
@Composable
fun rememberDictation(
    language: String,
    model: SpeechModel,
    onText: (text: String, final: Boolean) -> Unit,
    onStopped: () -> Unit,
    onError: (String) -> Unit,
): Dictation {
    val models = koinInject<SpeechModels>()
    val scope = rememberCoroutineScope()
    val askMic = rememberMicPermission()
    val text by rememberUpdatedState(onText)
    val stopped by rememberUpdatedState(onStopped)
    val error by rememberUpdatedState(onError)
    val dictation = remember {
        OfflineDictation(scope, models, { t -> text(t, true) }, { stopped() }, { error(it) })
    }
    dictation.language = language
    dictation.model = model
    dictation.askMic = askMic
    DisposableEffect(Unit) { onDispose { dictation.shutdown() } }
    return dictation
}

private const val MIC_DENIED = "Allow the microphone in the app's settings to dictate."

private class OfflineDictation(
    private val scope: CoroutineScope,
    private val models: SpeechModels,
    private val onSentence: (String) -> Unit,
    private val onStopped: () -> Unit,
    private val onError: (String) -> Unit,
) : Dictation {
    var language = ""
    var model = SpeechModel.Base
    var askMic: (onResult: (Boolean) -> Unit) -> Unit = { it(true) }

    override var listening by mutableStateOf(false)
    override var level by mutableFloatStateOf(0f)
    override var ready by mutableStateOf(false)
    override var status by mutableStateOf<String?>(null)
    override var pendingDownload by mutableStateOf<SpeechModel?>(null)

    @Volatile private var keepListening = false
    private var job: Job? = null

    // The loaded model stays loaded while this screen is open (loading takes seconds).
    private var transcriber: Transcriber? = null
    private var loadedKey = ""
    private var closed = false

    override fun start() {
        if (job?.isActive == true) return
        if (!models.isInstalled(model)) {
            pendingDownload = model // the screen asks "Download speech model?"
            return
        }
        askMic { granted -> if (granted) listen() else onError(MIC_DENIED) }
    }

    override fun stop() {
        if (!keepListening) return
        keepListening = false // the recorder stops; the last sentence is still written
        ready = false
        level = 0f
        status = "Writing…"
    }

    override fun confirmDownload() {
        val m = pendingDownload ?: return
        pendingDownload = null
        job = scope.launch {
            status = "Downloading speech model… 0%"
            val ok = try {
                models.download(m) { p -> status = "Downloading speech model… ${(p * 100).toInt()}%" }
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                false
            } finally {
                status = null
            }
            job = null // this download is done; listening is a new job
            if (ok) askMic { granted -> if (granted) listen() else onError(MIC_DENIED) }
            else onError("The download didn't finish. Check your internet and tap the mic to try again.")
        }
    }

    override fun cancelDownload() {
        pendingDownload = null
    }

    private fun listen() {
        if (job?.isActive == true || closed) return
        job = scope.launch {
            listening = true
            ready = false
            var recorder: Job? = null
            try {
                // 1. Load Whisper (only the first time, or after changing language/model).
                val key = "${model.id}|$language"
                val t = transcriber?.takeIf { loadedKey == key } ?: run {
                    status = "Loading speech model…"
                    transcriber?.let { old -> withContext(Dispatchers.Default) { old.release() } }
                    transcriber = null
                    val fresh = withContext(Dispatchers.Default) {
                        Transcriber(models.paths(model), language, speechThreads())
                    }
                    transcriber = fresh
                    loadedKey = key
                    fresh
                }
                status = null

                // 2. Record on a background thread; finished sentences go into a queue...
                val sentences = Channel<FloatArray>(Channel.UNLIMITED)
                keepListening = true
                recorder = launch(Dispatchers.Default) {
                    val mic = Microphone()
                    if (!mic.open()) {
                        sentences.close(MicUnavailable())
                        return@launch
                    }
                    ready = true
                    val buffer = FloatArray(512)
                    try {
                        while (keepListening && isActive) {
                            val n = mic.read(buffer)
                            if (n <= 0) continue
                            val chunk = buffer.copyOf(n)
                            level = loudness(chunk)
                            t.accept(chunk)
                            while (true) sentences.trySend(t.nextSentence() ?: break)
                        }
                    } finally {
                        mic.close()
                        t.flush()
                        while (true) sentences.trySend(t.nextSentence() ?: break)
                        sentences.close()
                    }
                }

                // 3. ...and are written out one by one, while you keep talking.
                try {
                    for (audio in sentences) {
                        val raw = withContext(Dispatchers.Default) { t.transcribe(audio) }
                        val clean = cleanTranscript(raw)
                        if (clean.isNotEmpty()) onSentence(clean)
                    }
                } catch (_: MicUnavailable) {
                    onError("Couldn't use the microphone.")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onError("Dictation couldn't start. Try another speech model in Settings.")
            } finally {
                keepListening = false
                // The recorder must be finished before the model can be freed.
                withContext(NonCancellable) { recorder?.join() }
                listening = false
                ready = false
                level = 0f
                status = null
                if (closed) releaseModel()
                onStopped()
            }
        }
    }

    /** The screen closed: stop, and free the model's memory once nothing uses it. */
    fun shutdown() {
        closed = true
        keepListening = false
        if (job?.isActive != true) releaseModel()
    }

    private fun releaseModel() {
        transcriber?.release()
        transcriber = null
        loadedKey = ""
    }
}

private class MicUnavailable : Exception()

/** Root-mean-square loudness of a chunk, scaled to 0..1 for the mic animation. */
private fun loudness(samples: FloatArray): Float {
    var sum = 0f
    for (s in samples) sum += s * s
    return (sqrt(sum / samples.size.coerceAtLeast(1)) * 8f).coerceIn(0f, 1f)
}

/**
 * Tidies Whisper's output: drops its notes for non-speech ([BLANK_AUDIO], (music)),
 * filler sounds (um, uh), stuttered repeats ("I I went" -> "I went"), and the phrases it
 * sometimes "hears" in silence ("Thank you.").
 */
internal fun cleanTranscript(raw: String): String {
    var t = raw.trim()
    t = t.replace(Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*"""), " ")
    t = t.replace(Regex("""(?i)\b(u+m+|u+h+|e+r+m+|h+m+|m+h*m+|a+h+)\b[,.]?"""), " ")
    t = t.replace(Regex("""(?i)\b(\w+)(?:[,]?\s+\1\b)+"""), "$1")
    t = t.replace(Regex("""\s+([,.!?।])"""), "$1")
    t = t.replace(Regex("""\s+"""), " ").trim().trimStart(',', '.', ' ')
    val bare = t.lowercase().trim('.', '!', '?', ' ')
    if (bare in SilencePhrases) return ""
    return t
}

private val SilencePhrases = setOf(
    "", "you", "thank you", "thanks", "thank you for watching", "thanks for watching", "bye",
)

/** "Download speech model?" — shown the first time someone taps the mic. */
@Composable
fun DictationDialogs(dictation: Dictation) {
    val model = dictation.pendingDownload ?: return
    AlertDialog(
        onDismissRequest = dictation::cancelDownload,
        title = { Text("Download speech model?") },
        text = {
            Text(
                "Dictation runs on this device, so it works offline and your voice never leaves it. " +
                    "It needs a one-time download of about ${model.sizeMb} MB (${model.label}). Wi-Fi is a good idea.",
            )
        },
        confirmButton = { TextButton(onClick = dictation::confirmDownload) { Text("Download") } },
        dismissButton = { TextButton(onClick = dictation::cancelDownload) { Text("Not now") } },
    )
}

/** The mic button: tap to talk, tap again to stop. Pulses with your voice while listening. */
@Composable
fun MicButton(dictation: Dictation, modifier: Modifier = Modifier) {
    val listening = dictation.listening
    val pulse by animateFloatAsState(if (dictation.ready) 1f + dictation.level * 0.6f else 1f, Motion.gentleSpring(), label = "mic pulse")
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) {
                when {
                    dictation.ready -> dictation.stop()
                    !listening && dictation.status == null -> dictation.start()
                }
            }
            .semantics { contentDescription = if (listening) "Stop dictation" else "Dictate: speak to write" },
        contentAlignment = Alignment.Center,
    ) {
        if (listening) {
            Box(
                Modifier
                    .size(34.dp)
                    .graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    }
                    .clip(CircleShape)
                    .background(JournalColors.Accent.copy(alpha = 0.22f)),
            )
            Box(Modifier.size(34.dp).clip(CircleShape).background(JournalColors.AccentContainer))
        }
        LineIcon(Glyph.Mic, if (listening) JournalColors.Accent else Color(0xFFC9CEDB))
    }
}
