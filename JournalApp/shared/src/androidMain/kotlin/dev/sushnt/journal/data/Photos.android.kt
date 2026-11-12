package dev.sushnt.journal.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

actual fun preparePhoto(bytes: ByteArray): PreparedPhoto? {
    val decoded = try {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder turns phone photos upright (EXIF rotation) and can shrink while decoding.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > PHOTO_MAX_SIDE) {
                    val scale = PHOTO_MAX_SIDE.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeSampled(bytes)
        }
    } catch (_: Exception) {
        null
    } ?: return null

    val longest = max(decoded.width, decoded.height)
    val bitmap = if (longest > PHOTO_MAX_SIDE) {
        val scale = PHOTO_MAX_SIDE.toFloat() / longest
        Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
    } else decoded

    // JPEG has no transparency: put the picture on white.
    val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    Canvas(opaque).apply {
        drawColor(Color.WHITE)
        drawBitmap(bitmap, 0f, 0f, null)
    }
    val out = ByteArrayOutputStream()
    opaque.compress(Bitmap.CompressFormat.JPEG, 82, out) // re-encoding drops EXIF (location etc.)
    return PreparedPhoto(out.toByteArray(), opaque.width, opaque.height)
}

/** Android 7–8: decode at a reduced size so a 50 MP photo doesn't run out of memory. */
private fun decodeSampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_MAX_SIDE) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

actual fun writeFileBytes(path: String, bytes: ByteArray) {
    val file = File(path)
    file.parentFile?.mkdirs()
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
