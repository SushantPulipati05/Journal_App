package dev.sushnt.journal.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val SkeletonBase = Color(0xFF1A2132)
private val SkeletonShine = Color(0xFF262E45)

/**
 * A soft light sweeping across a grey shape: "something is loading here".
 * Every skeleton on screen shares the same 1.3 s sweep, so they move together.
 */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer sweep",
    )
    drawBehind {
        val band = size.width.coerceAtLeast(240f)
        val x = progress * size.width
        drawRect(SkeletonBase)
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color.Transparent, SkeletonShine, Color.Transparent),
                start = Offset(x - band / 2, 0f),
                end = Offset(x + band / 2, size.height),
            ),
        )
    }
}

/** One grey placeholder bar or block. */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp = 12.dp,
    shape: Shape = RoundedCornerShape(6.dp),
) {
    Box(
        (if (width != null) modifier.width(width) else modifier.fillMaxWidth())
            .height(height)
            .clip(shape)
            .shimmer(),
    )
}

@Composable
fun SkeletonCircle(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).shimmer())
}

/**
 * The shape of an entry card while entries load: date, title and a few lines of text.
 * [lines] varies the height so a grid of these looks like a real gallery.
 */
@Composable
fun SkeletonEntryCard(modifier: Modifier = Modifier, lines: Int = 2, titleFraction: Float = 0.7f) {
    Column(
        modifier
            .fillMaxWidth()
            .card()
            .padding(16.dp)
            .semantics { contentDescription = "Loading entries" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row {
            SkeletonBlock(width = 70.dp, height = 10.dp)
            Spacer(Modifier.weight(1f))
            SkeletonBlock(width = 46.dp, height = 10.dp)
        }
        SkeletonBlock(Modifier.fillMaxWidth(titleFraction), height = 18.dp)
        repeat(lines) { i ->
            SkeletonBlock(if (i == lines - 1) Modifier.fillMaxWidth(0.55f) else Modifier, height = 11.dp)
        }
    }
}

/** Placeholder rows shaped like a timeline day: big date on the left, paragraphs on the right. */
@Composable
fun SkeletonTimelineDay(modifier: Modifier = Modifier, paragraphLines: Int = 4) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A2132)))
        Row(Modifier.fillMaxWidth().padding(vertical = 26.dp)) {
            Column(Modifier.width(84.dp), horizontalAlignment = androidx.compose.ui.Alignment.End) {
                SkeletonBlock(width = 46.dp, height = 40.dp, shape = RoundedCornerShape(8.dp))
                Spacer(Modifier.height(8.dp))
                SkeletonBlock(width = 60.dp, height = 10.dp)
            }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(width = 60.dp, height = 10.dp)
                SkeletonBlock(Modifier.fillMaxWidth(0.5f), height = 22.dp)
                repeat(paragraphLines) { i ->
                    SkeletonBlock(if (i == paragraphLines - 1) Modifier.fillMaxWidth(0.4f) else Modifier, height = 13.dp)
                }
            }
        }
    }
}
