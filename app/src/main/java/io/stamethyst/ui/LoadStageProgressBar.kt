package io.stamethyst.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.stamethyst.R

/**
 * Everything a [LoadStageProgressBar] draws for one stage of a load.
 *
 * Callers map their own phase enum onto this so the market, the AI patch catalogue and any later
 * surface narrate a load with the same widget instead of a second copy of it.
 */
internal data class LoadStageProgress(
    val label: String,
    /** 0..1 target of the determinate track. */
    val fraction: Float,
    val detail: String? = null,
    val accentColor: Color,
    val failure: Boolean = false,
    /** False once the stage is terminal, which stops the liveness pulse. */
    val animating: Boolean = true,
)

/**
 * Height the progress row occupies when fully revealed.
 *
 * The height is declared rather than measured on purpose. Callers reserve list inset from the same
 * animated value that drives this row, and that only stays in sync if the row never reports its own
 * size back through state: doing so puts the list a frame behind the header and reads as a
 * stutter. Because it is declared, it must also follow the user's font scale, otherwise the phase
 * label and detail line would clip at large text sizes.
 */
@Composable
internal fun loadStageProgressBarHeight(): Dp {
    val fontScale = LocalDensity.current.fontScale
    return remember(fontScale) {
        val textScale = fontScale.coerceIn(1f, 2f)
        ProgressLabelRowHeight * textScale +
            ProgressLabelTrackGap +
            ProgressTrackHeight +
            ProgressTrackDetailGap +
            ProgressDetailHeight * textScale +
            ProgressBottomPadding
    }
}

private val ProgressLabelRowHeight = 20.dp
private val ProgressLabelTrackGap = 6.dp
private val ProgressTrackHeight = 4.dp
private val ProgressTrackDetailGap = 4.dp
private val ProgressDetailHeight = 16.dp
private val ProgressBottomPadding = 12.dp
private val ProgressPercentWidth = 44.dp

/**
 * Narrates a load with a named stage, a percent and a determinate track.
 *
 * A load pipeline usually has no byte-level progress to report, so an indeterminate spinner would
 * say nothing about *why* it is slow. This instead advances a determinate bar through named stages
 * and can call out recoveries (node failover, official fallback), which is the information a user
 * needs when an acceleration node is degraded.
 *
 * @param revealHeight animated height owned by the caller, so the surrounding header and the list
 *   inset grow from one shared value instead of reacting to this row's measured size.
 */
@Composable
internal fun LoadStageProgressBar(
    progress: LoadStageProgress,
    revealHeight: Dp,
    modifier: Modifier = Modifier,
) {
    if (revealHeight <= 0.dp) return

    val fullHeight = loadStageProgressBarHeight()
    val textScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val revealFraction = (revealHeight / fullHeight).coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    // Colour is animated rather than switched so a failover reads as the same continuous operation
    // changing state, not as a different widget appearing.
    val accentColor by animateColorAsState(
        targetValue = progress.accentColor,
        animationSpec = tween(durationMillis = 320),
        label = "loadStageAccent",
    )
    val fraction by animateFloatAsState(
        targetValue = progress.fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 420, easing = LinearEasing),
        label = "loadStageFraction",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(revealHeight)
            .clipToBounds()
            // Fading through a graphics layer keeps the reveal a draw-only change; animating alpha on
            // the children would invalidate layout on every frame.
            .graphicsLayer { alpha = revealFraction },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(fullHeight)
                .padding(start = 16.dp, end = 16.dp, bottom = ProgressBottomPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ProgressLabelRowHeight * textScale),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoadStagePhasePulse(
                    color = accentColor,
                    animating = progress.animating,
                )
                AnimatedContent(
                    targetState = progress.label,
                    transitionSpec = {
                        (
                            fadeIn(animationSpec = tween(durationMillis = 200)) +
                                slideInVertically(
                                    animationSpec = tween(durationMillis = 220),
                                ) { height -> height / 2 }
                            ) togetherWith (
                            fadeOut(animationSpec = tween(durationMillis = 140)) +
                                slideOutVertically(
                                    animationSpec = tween(durationMillis = 220),
                                ) { height -> -height / 2 }
                            // Snap the container: the row is a fixed size, so animating it would only
                            // add a layout pass per frame without changing what is drawn.
                            ) using SizeTransform(clip = false) { _, _ -> snap() }
                    },
                    label = "loadStageLabel",
                    modifier = Modifier.weight(1f),
                ) { animatedLabel ->
                    Text(
                        text = animatedLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (progress.failure) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.load_stage_progress_percent, (fraction * 100f).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    // The number changes on almost every animation frame. A fixed width stops each
                    // change from re-measuring the row and re-laying out the label beside it.
                    modifier = Modifier.width(ProgressPercentWidth),
                )
            }

            Spacer(modifier = Modifier.height(ProgressLabelTrackGap))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ProgressTrackHeight)
                    .drawBehind {
                        val radius = CornerRadius(size.height / 2f)
                        drawRoundRect(color = trackColor, cornerRadius = radius)
                        // Drawn from x = 0 so the fill always grows left to right. Doing this in the
                        // draw phase also keeps the sweep off the layout path entirely.
                        val fillWidth = size.width * fraction
                        if (fillWidth > 0f) {
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(accentColor.copy(alpha = 0.72f), accentColor),
                                    startX = 0f,
                                    endX = size.width,
                                ),
                                topLeft = Offset.Zero,
                                size = Size(fillWidth.coerceAtMost(size.width), size.height),
                                cornerRadius = radius,
                            )
                        }
                    },
            )

            Spacer(modifier = Modifier.height(ProgressTrackDetailGap))

            // The detail slot is always reserved. Letting it expand and collapse would animate the
            // height a second time, on top of the reveal, and the two fought each other.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ProgressDetailHeight * textScale),
                contentAlignment = Alignment.CenterStart,
            ) {
                Crossfade(
                    targetState = progress.detail.orEmpty(),
                    animationSpec = tween(durationMillis = 200),
                    label = "loadStageDetail",
                ) { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (progress.failure) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Breathing dot that conveys liveness without competing with the bar itself. */
@Composable
internal fun LoadStagePhasePulse(
    color: Color,
    animating: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "loadStagePulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = if (animating) 0.35f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadStagePulseAlpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = if (animating) pulseAlpha else 1f)),
    )
}
