package dev.sushnt.journal.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The app's logo: a violet pen nib (the "Kalam" design). Drawn from code, so it is
 * sharp at every size and needs no image files. Same shapes as the Android launcher
 * icon in androidApp/src/main/res.
 */

private val NibInk = Color(0xFF1A1433)
private val NibFill = Brush.linearGradient(
    listOf(Color(0xFFC4B5FD), Color(0xFF8B5CF6)),
    start = Offset(36f, 16f),
    end = Offset(86f, 104f),
)

private fun ImageVector.Builder.nib(): ImageVector.Builder = apply {
    // The nib
    path(fill = NibFill) {
        moveTo(60f, 16f)
        lineTo(84f, 56f)
        quadTo(85f, 72f, 71f, 84f)
        lineTo(60f, 104f)
        lineTo(49f, 84f)
        quadTo(35f, 72f, 36f, 56f)
        close()
    }
    // The slit...
    path(stroke = SolidColor(NibInk), strokeLineWidth = 3f, strokeLineCap = StrokeCap.Round) {
        moveTo(60f, 60f)
        lineTo(60f, 100f)
    }
    // ...and the breather hole
    path(fill = SolidColor(NibInk)) { circle(60f, 56f, 5.5f) }
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, false, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, false, -2 * r, 0f)
    close()
}

/** Just the nib, on a transparent background (sidebar, sign-in screen). */
val LogoMark: ImageVector by lazy {
    ImageVector.Builder("LogoMark", 24.dp, 24.dp, 120f, 120f).nib().build()
}

/** The full app icon: nib on a dark violet rounded square (desktop window and taskbar). */
val AppIcon: ImageVector by lazy {
    ImageVector.Builder("AppIcon", 48.dp, 48.dp, 120f, 120f).apply {
        val r = 28f
        fun PathBuilder.roundedSquare() {
            moveTo(r, 0f)
            lineTo(120f - r, 0f)
            arcTo(r, r, 0f, false, true, 120f, r)
            lineTo(120f, 120f - r)
            arcTo(r, r, 0f, false, true, 120f - r, 120f)
            lineTo(r, 120f)
            arcTo(r, r, 0f, false, true, 0f, 120f - r)
            lineTo(0f, r)
            arcTo(r, r, 0f, false, true, r, 0f)
            close()
        }
        path(fill = Brush.linearGradient(listOf(Color(0xFF231B45), Color(0xFF0B0E17)), start = Offset.Zero, end = Offset(120f, 120f))) { roundedSquare() }
        path(fill = Brush.radialGradient(listOf(Color(0x47A78BFA), Color(0x00A78BFA)), center = Offset(60f, 36f), radius = 72f)) { roundedSquare() }
    }.nib().build()
}

/** The logo at [size], for use inside the app. Decorative: the name next to it is the label. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(LogoMark), contentDescription = null, modifier = modifier.size(size))
}
