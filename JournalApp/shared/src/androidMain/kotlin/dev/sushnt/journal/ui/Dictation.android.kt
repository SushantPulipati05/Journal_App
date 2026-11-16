package dev.sushnt.journal.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android's built-in speech recognition (usually Google's). Free, and words appear as you
 * speak.
 *
 * Older Android stops listening after every pause, so we start it again until you tap stop.
 * Words said in the gap while it restarts used to be lost; to keep that gap tiny we restart
 * the moment a sentence ends, and on Android 13+ we use a "segmented session", which keeps
 * listening across pauses with no restarts at all.
 */
@Composable
actual fun rememberDictation(
    language: String,
    onText: (text: String, final: Boolean) -> Unit,
    onStopped: () -> Unit,
    onError: (String) -> Unit,
): Dictation? {
    val context = LocalContext.current
    if (!SpeechRecognizer.isRecognitionAvailable(context)) return null

    val text by rememberUpdatedState(onText)
    val stopped by rememberUpdatedState(onStopped)
    val error by rememberUpdatedState(onError)
    val dictation = remember {
        AndroidDictation(context.applicationContext, { t, f -> text(t, f) }, { stopped() }, { error(it) })
    }
    // Asking for the microphone the first time.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) dictation.begin() else error("Allow the microphone in the app's settings to dictate.")
    }
    dictation.askPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    dictation.language = language
    DisposableEffect(Unit) { onDispose { dictation.release() } }
    return dictation
}

private class AndroidDictation(
    private val context: Context,
    private val onText: (String, Boolean) -> Unit,
    private val onStopped: () -> Unit,
    private val onError: (String) -> Unit,
) : Dictation, RecognitionListener {
    override var listening by mutableStateOf(false)
    override var level by mutableFloatStateOf(0f)
    override var ready by mutableStateOf(false)

    /** e.g. "en-IN". Picking the right one (Indian English vs US) matters a lot for accuracy. */
    var language: String = ""
    var askPermission: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** The latest unfinished guess: written for good if listening ends unexpectedly. */
    private var pending = ""

    /** Restarts in a row that heard nothing; after a while we stop to save battery. */
    private var quietRestarts = 0

    /**
     * Android 13+: one long session that keeps going across pauses. Switched off if this
     * phone's speech service refuses it.
     */
    private var segmented = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Android 13+: Google's on-device recognizer (the one behind Live Caption and the
     * Recorder app). Unlike the default one it really adds punctuation and capitals, and it
     * keeps listening across pauses. Falls back to the default one if it can't be used.
     */
    private var onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Anything recognised since the mic was turned on. */
    private var heardAnything = false

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Online recognition is far more accurate than the offline pack; it still falls
        // back to offline when there's no internet.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        if (language.isNotEmpty()) {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
        }
        // Wait longer before deciding you've finished (some phones respect this).
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Punctuation and capitals, tuned for accuracy rather than speed.
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
        }
        if (segmented && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Keep listening across pauses, for up to 5 minutes per session.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5 * 60_000L)
        }
    }

    override fun start() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermission()
            return
        }
        begin()
    }

    fun begin() {
        if (listening) return
        listening = true
        ready = false
        pending = ""
        quietRestarts = 0
        heardAnything = false
        listenAgain()
    }

    private fun listenAgain() {
        if (!listening) return
        val r = recognizer ?: (
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        ).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        r.startListening(intent())
    }

    override fun stop() {
        if (!listening) return
        listening = false
        recognizer?.stopListening() // the last words still arrive in onResults
        main.postDelayed({ finish() }, 1_500) // in case they never do
    }

    /** Listening is over: write any half-finished guess for good and tidy up. */
    private fun finish() {
        if (recognizer == null && !ready && pending.isEmpty()) return
        commitPending()
        listening = false
        ready = false
        level = 0f
        recognizer?.destroy()
        recognizer = null
        main.removeCallbacksAndMessages(null)
        onStopped()
    }

    fun release() {
        listening = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun commitPending() {
        if (pending.isNotBlank()) onText(punctuate(cleanTranscript(pending), language), true)
        pending = ""
    }

    private fun Bundle.best(): String =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    // ---------- RecognitionListener ----------

    override fun onReadyForSpeech(params: Bundle?) {
        ready = true
    }

    override fun onBeginningOfSpeech() {
        quietRestarts = 0
    }

    override fun onRmsChanged(rmsdB: Float) {
        // Roughly -2 (silence) .. 10 (loud).
        level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val words = partialResults?.best().orEmpty()
        if (words.isBlank()) return
        pending = words
        heardAnything = true
        onText(words, false)
    }

    /** One finished sentence (older Android): write it, and listen again straight away. */
    override fun onResults(results: Bundle?) {
        val words = results?.best().orEmpty()
        pending = ""
        if (listening) listenAgain() // restart first, so the gap where words get lost is tiny
        if (words.isNotBlank()) onText(punctuate(cleanTranscript(words), language), true)
        if (!listening) finish()
    }

    /** Android 13+: a sentence finished, but the session keeps listening. */
    override fun onSegmentResults(segmentResults: Bundle) {
        val words = segmentResults.best()
        pending = ""
        if (words.isNotBlank()) {
            quietRestarts = 0
            onText(punctuate(cleanTranscript(words), language), true)
        }
    }

    /** Android 13+: the long session ended (5 minutes, or you tapped stop). */
    override fun onEndOfSegmentedSession() {
        commitPending()
        if (listening) listenAgain() else finish()
    }

    override fun onError(error: Int) {
        commitPending()
        when (error) {
            // Heard nothing / nothing it understood: just keep listening, up to ~1 minute of quiet.
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (listening && ++quietRestarts <= 8) {
                    listenAgain()
                    return
                }
            }
            // Still busy finishing the last session: start fresh a moment later.
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> if (listening && ++quietRestarts <= 8) {
                recognizer?.destroy()
                recognizer = null
                main.postDelayed({ listenAgain() }, 300)
                return
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                onError("Allow the microphone in the app's settings to dictate.")
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                if (listening) onError("No internet. Download the offline speech pack for your language in the phone's settings.")
            // The on-device recognizer hasn't got this language yet: ask the phone to download
            // it (for next time) and carry on with the default recognizer now.
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> if (onDevice && listening) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { recognizer?.triggerModelDownload(intent()) }
                }
                switchToDefaultRecognizer()
                onError("Downloading the speech pack for punctuation. Until then, dictation works without it.")
                return
            } else {
                onError("This language isn't available for dictation on your phone.")
            }
            // Anything else right at the start: the phone's speech service may not support the
            // long Android 13 session. Try once more the classic way before giving up.
            else -> if (listening && onDevice && !heardAnything) {
                switchToDefaultRecognizer()
                return
            } else if (listening && segmented && !heardAnything) {
                segmented = false
                recognizer?.destroy()
                recognizer = null
                main.postDelayed({ listenAgain() }, 300)
                return
            }
        }
        listening = false
        finish()
    }

    /** Stop using the on-device recognizer for this session and listen with the default one. */
    private fun switchToDefaultRecognizer() {
        onDevice = false
        recognizer?.destroy()
        recognizer = null
        main.postDelayed({ listenAgain() }, 300)
    }

    override fun onEndOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
