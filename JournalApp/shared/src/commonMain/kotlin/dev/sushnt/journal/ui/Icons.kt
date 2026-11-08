package dev.sushnt.journal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Glyph { Pen, Book, Person, Search, Back, Check, Moon, More, Panel, Trash }

/**
 * Simple line icons drawn on a 24×24 grid (same shapes as the mockups),
 * so we don't need an icon library. Purely decorative: the button next to
 * or around each icon carries the text or contentDescription.
 */
@Composable
fun LineIcon(glyph: Glyph, tint: Color, size: Dp = 18.dp, strokeWidth: Float = 1.8f) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val stroke = Stroke(width = strokeWidth * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(vararg pts: Float) {
            val path = Path()
            path.moveTo(pts[0] * s, pts[1] * s)
            var i = 2
            while (i < pts.size) {
                path.lineTo(pts[i] * s, pts[i + 1] * s)
                i += 2
            }
            drawPath(path, tint, style = stroke)
        }
        fun circle(cx: Float, cy: Float, r: Float) =
            drawCircle(tint, radius = r * s, center = p(cx, cy), style = stroke)

        when (glyph) {
            Glyph.Pen -> {
                line(12f, 20f, 21f, 20f)
                line(16.5f, 3.5f, 19.5f, 6.5f, 7f, 19f, 3f, 20f, 4f, 16f, 16.5f, 3.5f)
            }
            Glyph.Book -> {
                line(4f, 19.5f, 4f, 5f, 6f, 3f, 19f, 3f, 19f, 19f, 6f, 19f, 4f, 21f)
                line(19f, 17f, 19f, 21f, 6f, 21f)
                line(9f, 7f, 15f, 7f)
            }
            Glyph.Person -> {
                circle(12f, 8f, 4f)
                drawArc(tint, 180f, 180f, false, topLeft = p(4f, 13f), size = Size(16f * s, 16f * s), style = stroke)
            }
            Glyph.Search -> {
                circle(11f, 11f, 7f)
                line(20f, 20f, 16.5f, 16.5f)
            }
            Glyph.Back -> line(15f, 18f, 9f, 12f, 15f, 6f)
            Glyph.Check -> line(20f, 6f, 9f, 17f, 4f, 12f)
            Glyph.Moon -> drawArc(tint, 100f, 260f, false, topLeft = p(3f, 3f), size = Size(18f * s, 18f * s), style = stroke)
            Glyph.Panel -> {
                drawRoundRect(
                    tint,
                    topLeft = p(3f, 4f),
                    size = Size(18f * s, 16f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * s, 2f * s),
                    style = stroke,
                )
                line(9f, 4f, 9f, 20f)
            }
            Glyph.Trash -> {
                line(3f, 6f, 21f, 6f)
                line(8f, 6f, 8f, 4f, 16f, 4f, 16f, 6f)
                line(19f, 6f, 18f, 20f, 6f, 20f, 5f, 6f)
                line(10f, 11f, 10f, 17f)
                line(14f, 11f, 14f, 17f)
            }
            Glyph.More -> {
                drawCircle(tint, radius = 1.6f * s, center = p(5f, 12f))
                drawCircle(tint, radius = 1.6f * s, center = p(12f, 12f))
                drawCircle(tint, radius = 1.6f * s, center = p(19f, 12f))
            }
        }
    }
}
