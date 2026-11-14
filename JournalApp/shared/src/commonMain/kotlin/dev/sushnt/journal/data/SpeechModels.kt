package dev.sushnt.journal.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Offline dictation runs OpenAI's Whisper on the device (through sherpa-onnx). The model is
 * too big to ship inside the app, so it's downloaded once, the first time you use the mic,
 * and kept in the app's data folder. After that, dictation works with no internet at all.
 */

/** The Whisper sizes on offer: bigger = more accurate, but slower and a bigger download. */
enum class SpeechModel(val id: String, val label: String, val detail: String, internal val repo: String) {
    Tiny("tiny", "Fast", "Quick on older phones, makes more mistakes", "csukuangfj/sherpa-onnx-whisper-tiny"),
    Base("base", "Balanced", "Good for most phones", "csukuangfj/sherpa-onnx-whisper-base"),
    Small("small", "Most accurate", "Best results, needs a fast phone or a computer", "csukuangfj/sherpa-onnx-whisper-small");

    /** Download size, all files together. */
    val sizeMb: Int get() = ((files.sumOf { it.bytes } + VAD_BYTES) / 1_000_000).toInt()

    internal val files: List<ModelFile>
        get() = listOf(
            ModelFile("$id-encoder.int8.onnx", ENCODER_BYTES.getValue(id)),
            ModelFile("$id-decoder.int8.onnx", DECODER_BYTES.getValue(id)),
            ModelFile("$id-tokens.txt", 816_730),
        )

    companion object {
        fun fromId(id: String): SpeechModel? = entries.firstOrNull { it.id == id }
    }
}

internal class ModelFile(val name: String, val bytes: Long)

private val ENCODER_BYTES = mapOf("tiny" to 12_937_772L, "base" to 29_120_534L, "small" to 112_442_483L)
private val DECODER_BYTES = mapOf("tiny" to 89_855_401L, "base" to 130_672_026L, "small" to 262_226_114L)

/** Silero VAD: the tiny model that hears where your sentences start and stop. */
private const val VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
private const val VAD_BYTES = 2_000_000L

/** Where each file of a model lives on this device. */
class SpeechModelPaths(val encoder: String, val decoder: String, val tokens: String, val vad: String)

/** Downloads, finds and deletes the speech models on this device. */
class SpeechModels(dataDir: String) {
    private val root = "$dataDir/speech"

    private fun dir(m: SpeechModel) = "$root/${m.id}"
    private val vadPath = "$root/silero_vad.onnx"

    fun paths(m: SpeechModel) = SpeechModelPaths(
        encoder = "${dir(m)}/${m.id}-encoder.int8.onnx",
        decoder = "${dir(m)}/${m.id}-decoder.int8.onnx",
        tokens = "${dir(m)}/${m.id}-tokens.txt",
        vad = vadPath,
    )

    /** Every file is there (a half-finished download doesn't count). */
    fun isInstalled(m: SpeechModel): Boolean =
        fileLength(vadPath) > 0 && m.files.all { fileLength("${dir(m)}/${it.name}") > 0 }

    /** Downloads what's missing. [onProgress] goes from 0 to 1. Throws if the internet fails. */
    suspend fun download(m: SpeechModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.Default) {
        makeDirs(dir(m))
        val jobs = buildList {
            if (fileLength(vadPath) <= 0) add(Triple(VAD_URL, vadPath, VAD_BYTES))
            for (f in m.files) {
                val path = "${dir(m)}/${f.name}"
                if (fileLength(path) <= 0) add(Triple("https://huggingface.co/${m.repo}/resolve/main/${f.name}", path, f.bytes))
            }
        }
        val total = jobs.sumOf { it.third }.coerceAtLeast(1)
        var done = 0L
        for ((url, path, expected) in jobs) {
            downloadToFile(url, path) { bytesSoFar ->
                onProgress(((done + bytesSoFar).toFloat() / total).coerceIn(0f, 1f))
            }
            done += expected
        }
        onProgress(1f)
    }

    fun delete(m: SpeechModel) {
        for (f in m.files) deleteFileAt("${dir(m)}/${f.name}")
    }
}

// ---- what each platform provides (SpeechFiles.jvm.kt / .android.kt / .ios.kt) ----

/** Size of the file in bytes, or -1 if it isn't there. */
expect fun fileLength(path: String): Long

/**
 * Downloads [url] to [path]: first into "path.part", renamed when complete, so an
 * interrupted download is never mistaken for a finished one. Blocking.
 */
expect fun downloadToFile(url: String, path: String, onBytes: (Long) -> Unit)
