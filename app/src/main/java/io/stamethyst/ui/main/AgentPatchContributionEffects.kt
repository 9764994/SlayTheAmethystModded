package io.stamethyst.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos

/** The approved web preview uses a 20 × 20 viewport, matching the standard icon badge. */
@Composable
internal fun AgentPatchContributionEffects(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "contributionEnergy")
    val sweep by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "goldSweep")
    val cyanTravel by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2900, easing = LinearEasing)), label = "cyanArc")
    val violetTravel by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(3700, easing = LinearEasing)), label = "violetArc")
    val glow by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(3800, easing = LinearEasing)), label = "arcGlow")
    val bolt = remember {
        Path().apply {
            moveTo(11.25f, 5f); lineTo(6.75f, 10.5f); lineTo(9.25f, 10.5f)
            lineTo(8.25f, 15f); lineTo(13.25f, 9f); lineTo(10.5f, 9f); close()
        }
    }
    val arcs = remember {
        val left = listOf(Offset(8.1f, 2.6f), Offset(5.3f, 3.8f), Offset(5.8f, 5.7f),
            Offset(3.4f, 7.6f), Offset(4.2f, 9.2f), Offset(2.8f, 11f),
            Offset(4.4f, 13.2f), Offset(4f, 15f), Offset(6.9f, 17f))
        val right = listOf(Offset(12f, 17.4f), Offset(14.8f, 16.2f), Offset(14.2f, 14.3f),
            Offset(16.6f, 12.4f), Offset(15.8f, 10.8f), Offset(17.2f, 9f),
            Offset(15.6f, 6.8f), Offset(16f, 5f), Offset(13.1f, 3f))
        listOf(left, right).map { points ->
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            path to PathMeasure().apply { setPath(path, false) }
        }
    }
    val highlight = remember { Path() }
    val star = remember {
        Path().apply {
            moveTo(0f, -1.1f); lineTo(.25f, -.25f); lineTo(1.1f, 0f)
            lineTo(.25f, .25f); lineTo(0f, 1.1f); lineTo(-.25f, .25f)
            lineTo(-1.1f, 0f); lineTo(-.25f, -.25f); close()
        }
    }
    Canvas(modifier) {
        scale(size.width / 20f, size.height / 20f, pivot = Offset.Zero) {
            // Restrict the gold band to exactly the same silhouette as the 12dp vector icon.
            val y = when {
                sweep <= .65f -> 1f + 18f * sweep / .65f
                sweep <= .76f -> 19f + 4f * (sweep - .65f) / .11f
                else -> 23f
            }
            val sweepAlpha = when {
                sweep < .08f -> sweep / .08f
                sweep <= .65f -> 1f
                sweep < .76f -> 1f - (sweep - .65f) / .11f
                else -> 0f
            }
            clipPath(bolt) {
                drawRect(Brush.verticalGradient(
                    0f to Color(0x00FFB52E), .22f to Color(0x99EFAD28),
                    .46f to Color(0xFFFFD365), .55f to Color(0xFFFFF4CF),
                    .65f to Color(0xFFFFDA72), .85f to Color(0xBFF6B735),
                    1f to Color(0x00F6B735), startY = y - 4f, endY = y + 3f,
                ), alpha = sweepAlpha)
                drawLine(Color(0xFFFFF6D6).copy(alpha = sweepAlpha * .8f),
                    Offset(5f, y - .1f), Offset(15f, y - .1f), strokeWidth = .32f)
            }
            val glowAlpha = .55f + .4f * ((1f - cos(glow * 2 * PI).toFloat()) / 2f)
            for ((index, arc) in arcs.withIndex()) {
                val (path, measure) = arc
                val color = if (index == 0) Color(0xFF53C7F2) else Color(0xFFAD85EB)
                val bright = if (index == 0) Color(0xFFC9F5FF) else Color(0xFFE6D2FF)
                drawPath(path, color.copy(alpha = .12f * glowAlpha),
                    style = Stroke(2.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, color.copy(alpha = .24f * glowAlpha),
                    style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, color.copy(alpha = .65f),
                    style = Stroke(.46f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val travel = if (index == 0) cyanTravel else 1f - violetTravel
                val head = travel * (measure.length + 2.4f)
                highlight.reset()
                if (measure.getSegment((head - 2.4f).coerceAtLeast(0f), head.coerceAtMost(measure.length), highlight)) {
                    drawPath(highlight, color.copy(alpha = .35f), style = Stroke(1.6f, cap = StrokeCap.Round))
                    drawPath(highlight, bright, style = Stroke(.74f, cap = StrokeCap.Round))
                }
            }
            for ((index, center) in listOf(Offset(6.1f, 3.5f), Offset(14.1f, 16.4f)).withIndex()) {
                val pulse = (1f - cos(((sweep + index * .5f) % 1f) * 2 * PI).toFloat()) / 2f
                val starScale = .65f + .5f * pulse
                withTransform({
                    translate(center.x, center.y)
                    scale(starScale, starScale, pivot = Offset.Zero)
                }) {
                    drawPath(star, Color(0xFFFFE7B2).copy(alpha = .3f + .7f * pulse))
                }
            }
        }
    }
}
