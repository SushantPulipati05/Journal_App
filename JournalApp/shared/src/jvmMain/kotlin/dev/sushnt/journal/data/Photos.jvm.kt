package dev.sushnt.journal.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

// Desktop uses Skia (the graphics engine Compose already ships with) for photos:
// it reads JPEG, PNG, WebP, GIF and BMP and turns phone photos upright.

actual fun preparePhoto(bytes: ByteArray): PreparedPhoto? {
    val image = try {
        Image.makeFromEncoded(bytes)
    } catch (_: Exception) {
        return null
    }
    val longest = max(image.width, image.height)
    if (longest <= 0) return null
    val scale = if (longest > PHOTO_MAX_SIDE) PHOTO_MAX_SIDE.toFloat() / longest else 1f
    val w = (image.width * scale).roundToInt().coerceAtLeast(1)
    val h = (image.height * scale).roundToInt().coerceAtLeast(1)

    val surface = Surface.makeRasterN32Premul(w, h)
    val canvas = surface.canvas
    canvas.clear(Color.WHITE) // JPEG has no transparency
    canvas.drawImageRect(
        image,
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(w.toFloat(), h.toFloat()),
        FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
        Paint(),
        true,
    )
    // Re-encoding drops all metadata (EXIF: location, camera, ...).
    val jpeg = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 82)?.bytes ?: return null
    return PreparedPhoto(jpeg, w, h)
}

actual fun decodeImage(bytes: ByteArray, maxSide: Int?): ImageBitmap? = try {
    val image = Image.makeFromEncoded(bytes)
    val longest = max(image.width, image.height)
    if (maxSide == null || longest <= maxSide) {
        image.toComposeImageBitmap()
    } else {
        // Draw it once at the small size and keep only that.
        val scale = maxSide.toFloat() / longest
        val w = (image.width * scale).roundToInt().coerceAtLeast(1)
        val h = (image.height * scale).roundToInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeWH(w.toFloat(), h.toFloat()),
            FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
            Paint(),
            true,
        )
        surface.makeImageSnapshot().toComposeImageBitmap()
    }
} catch (_: Exception) {
    null
}

actual fun writeFileBytes(path: String, bytes: ByteArray) {
    val file = File(path)
    file.parentFile?.mkdirs()
    // Write to a temporary file first so a crash never leaves half a photo behind.
    val tmp = File("$path.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}

actual fun readFileBytes(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

actual fun deleteFileAt(path: String) {
    File(path).delete()
}

actual fun makeDirs(path: String) {
    File(path).mkdirs()
}
