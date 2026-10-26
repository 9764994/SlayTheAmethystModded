package io.stamethyst.ui.workshop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.stamethyst.backend.workshop.WorkshopLoadPhase
import io.stamethyst.backend.workshop.WorkshopLoadProgress
import io.stamethyst.ui.LoadStagePhasePulse
import io.stamethyst.ui.LoadStageProgress
import io.stamethyst.ui.LoadStageProgressBar
import io.stamethyst.ui.loadStageProgressBarHeight

@Composable
internal fun workshopLoadProgressBarHeight(): Dp = loadStageProgressBarHeight()

/**
 * Narrates a market load at the bottom of the header card.
 *
 * The browse pipeline has no byte-level progress to report, so an indeterminate spinner would say
 * nothing about *why* a load is slow. This instead advances a determinate bar through named stages
 * and calls out recoveries (node failover, official fallback), which is the information a user needs
 * when an acceleration node is degraded.
 *
 * @param revealHeight animated height owned by the caller, so the header card and the list inset
 * grow from one shared value instead of reacting to this row's measured size.
 */
@Composable
internal fun WorkshopLoadProgressBar(
    progress: WorkshopLoadProgress?,
    revealHeight: Dp,
    modifier: Modifier = Modifier,
) {
    // Keep the last non-null progress so the collapse animation can play with its text intact
    // instead of snapping to empty as soon as the load finishes.
    var lastProgress by remember { mutableStateOf<WorkshopLoadProgress?>(null) }
    if (progress != null) {
        lastProgress = progress
    }
    val rendered = progress ?: lastProgress
    if (revealHeight <= 0.dp || rendered == null) {
        return
    }
    val phase = rendered.phase
    LoadStageProgressBar(
        progress = LoadStageProgress(
            label = phase.progressLabel(rendered),
            fraction = phase.completionFraction(),
            detail = rendered.detailLine(),
            accentColor = workshopLoadPhaseAccentColor(phase),
            failure = phase == WorkshopLoadPhase.Failed,
            animating = !phase.isTerminal,
        ),
        revealHeight = revealHeight,
        modifier = modifier,
    )
}

/** Breathing dot, kept at the market's call site for the detail top bar. */
@Composable
internal fun WorkshopProgressPhasePulse(
    color: Color,
    animating: Boolean,
) {
    LoadStagePhasePulse(color = color, animating = animating)
}

/**
 * Accent colour for a load phase, shared by the market header bar and the detail top bar so both
 * surfaces read recoveries and failures identically.
 */
@Composable
internal fun workshopLoadPhaseAccentColor(phase: WorkshopLoadPhase): Color = when {
    phase == WorkshopLoadPhase.Failed -> MaterialTheme.colorScheme.error
    phase == WorkshopLoadPhase.FailingOver || phase == WorkshopLoadPhase.FallingBack ->
        MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}
