package dev.sushnt.journal.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Speech to text. Android: the phone's own speech recognition (usually Google's), words
 * appear as you speak. Windows desktop: opens Windows voice typing (the same as Win + H).
 */
interface Dictation {
    /** True while the app itself is listening (Android). */
    val listening: Boolean

    /** How loud you are right now, 0..1, for the pulsing mic. */
    val level: Float

    /** Listening AND the microphone is open: start talking. (Words said before this are lost.) */
    val ready: Boolean

    fun start()
    fun stop()
}

/**
 * [onText]: recognised words; [final] = false while they may still change.
 * [onStopped]: listening ended (button, or an error).
 * [onError]: a short message for the user.
 * null where dictation isn't available (no speech service on the phone, macOS/Linux desktop).
 */
@Composable
expect fun rememberDictation(
    /** BCP-47 tag such as "en-IN"; "" = the phone's own language. */
    language: String,
    onText: (text: String, final: Boolean) -> Unit,
    onStopped: () -> Unit,
    onError: (String) -> Unit,
): Dictation?

/**
 * Tidies recognised speech: drops filler sounds (um, uh, hmm), stuttered repeats
 * ("I I went" -> "I went") and stray notes like [noise].
 */
internal fun cleanTranscript(raw: String): String {
    var t = raw.trim()
    t = t.replace(Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*"""), " ")
    t = t.replace(Regex("""(?i)\b(u+m+|u+h+|e+r+m+|h+m+|m+h*m+|a+h+)\b[,.]?"""), " ")
    t = t.replace(Regex("""(?i)\b(\w+)(?:[,]?\s+\1\b)+"""), "$1")
    t = t.replace(Regex("""\s+([,.!?।])"""), "$1")
    t = t.replace(Regex("""\s+"""), " ").trim().trimStart(',', '.', ' ')
    return t
}

/** Words that start a question, for guessing "?" instead of "." (English). */
private val QuestionStarts = setOf(
    "do", "does", "did", "is", "are", "was", "were", "am", "can", "could", "would", "should", "will",
    "shall", "may", "might", "have", "has", "had", "what", "why", "how", "when", "where", "who", "whom",
    "whose", "which", "isn't", "aren't", "don't", "doesn't", "didn't", "can't", "won't", "shouldn't",
)

/**
 * Punctuation for a finished sentence. Older phones' speech recognition writes no
 * punctuation at all, so:
 *  - spoken commands work in English: "comma", "full stop", "question mark",
 *    "exclamation mark", "new line" / "new paragraph";
 *  - a pause ends a sentence: "." is added (or "?" when it starts like a question,
 *    "।" in Hindi and Marathi), unless the phone already punctuated it;
 *  - each sentence starts with a capital letter.
 */
internal fun punctuate(raw: String, language: String): String {
    var t = raw.trim()
    if (t.isEmpty()) return t
    val english = language.isEmpty() || language.startsWith("en")
    val phonePunctuates = t.any { it in ".?!," }
    if (english) {
        t = t.replace(Regex("""(?i)\s*\b(new paragraph|new line|next line)\b\s*"""), "\n")
        t = t.replace(Regex("""(?i)\s*\bcomma\b"""), ",")
        t = t.replace(Regex("""(?i)\s*\bfull stop\b"""), ".")
        t = t.replace(Regex("""(?i)\s*\bquestion mark\b"""), "?")
        t = t.replace(Regex("""(?i)\s*\bexclamation (mark|point)\b"""), "!")
        t = t.replace(Regex("""([,.?!])(?=\p{L})"""), "$1 ") // "word.next" -> "word. next" (not "3.5")
    }
    if (!phonePunctuates) {
        // "…went home new line" ends that sentence too.
        t = t.replace(Regex("""(?<=[\p{L}\p{N}])\n"""), ".\n")
        val last = t.lastOrNull()
        if (last != null && last !in ".?!,\n") {
            val sentence = t.substringAfterLast('\n').split(Regex("""[.?!]\s""")).last()
            val firstWord = sentence.trim().substringBefore(' ').lowercase()
            t += when {
                english && firstWord in QuestionStarts -> "?"
                language.startsWith("hi") || language.startsWith("mr") -> "।"
                else -> "."
            }
        }
    }
    // Capital letters at the start of each sentence.
    t = t.replaceFirstChar { it.uppercaseChar() }
    t = Regex("""([.?!]\s+|\n)(\p{Ll})""").replace(t) { it.groupValues[1] + it.groupValues[2].uppercase() }
    return t
}

/** The mic button: tap to talk, tap again to stop. Pulses with your voice while listening. */
@Composable
fun MicButton(dictation: Dictation, modifier: Modifier = Modifier) {
    val listening = dictation.listening
    val pulse by animateFloatAsState(if (listening && dictation.ready) 1f + dictation.level * 0.6f else 1f, Motion.gentleSpring(), label = "mic pulse")
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) { if (listening) dictation.stop() else dictation.start() }
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
