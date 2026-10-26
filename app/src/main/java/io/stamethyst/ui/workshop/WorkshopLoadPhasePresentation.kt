package io.stamethyst.ui.workshop

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopLoadPhase
import io.stamethyst.backend.workshop.WorkshopLoadProgress

internal fun WorkshopLoadPhase.completionFraction(): Float = when (this) {
    WorkshopLoadPhase.Preparing -> 0.06f
    WorkshopLoadPhase.Authenticating -> 0.18f
    WorkshopLoadPhase.ResolvingRoute -> 0.32f
    WorkshopLoadPhase.ProbingNodes -> 0.44f
    WorkshopLoadPhase.Connecting -> 0.58f
    // Recoveries deliberately do not rewind the bar: work already done is still valid, and a bar
    // that jumps backwards reads as a bug rather than as a retry.
    WorkshopLoadPhase.FailingOver -> 0.62f
    WorkshopLoadPhase.FallingBack -> 0.66f
    WorkshopLoadPhase.Parsing -> 0.82f
    WorkshopLoadPhase.LoadingCommunityDetail -> 0.88f
    WorkshopLoadPhase.LoadingDependencies -> 0.94f
    WorkshopLoadPhase.Completed -> 1f
    WorkshopLoadPhase.Failed -> 1f
}

@Composable
internal fun WorkshopLoadPhase.progressLabel(progress: WorkshopLoadProgress?): String = when (this) {
    WorkshopLoadPhase.Preparing -> stringResource(R.string.workshop_load_phase_preparing)
    WorkshopLoadPhase.Authenticating -> stringResource(R.string.workshop_load_phase_authenticating)
    WorkshopLoadPhase.ResolvingRoute -> stringResource(R.string.workshop_load_phase_resolving_route)
    WorkshopLoadPhase.ProbingNodes -> stringResource(R.string.workshop_load_phase_probing_nodes)
    WorkshopLoadPhase.Connecting -> stringResource(R.string.workshop_load_phase_connecting)
    WorkshopLoadPhase.FailingOver -> stringResource(
        R.string.workshop_load_phase_failing_over,
        progress?.failedTargetCount ?: 1,
    )
    WorkshopLoadPhase.FallingBack -> stringResource(R.string.workshop_load_phase_falling_back)
    WorkshopLoadPhase.Parsing -> stringResource(R.string.workshop_load_phase_parsing)
    WorkshopLoadPhase.LoadingCommunityDetail -> stringResource(R.string.workshop_load_phase_loading_community_detail)
    WorkshopLoadPhase.LoadingDependencies -> stringResource(R.string.workshop_load_phase_loading_dependencies)
    WorkshopLoadPhase.Completed -> stringResource(R.string.workshop_load_phase_completed)
    WorkshopLoadPhase.Failed -> stringResource(R.string.workshop_load_phase_failed)
}

@Composable
internal fun WorkshopLoadProgress.detailLine(): String? {
    val host = target?.substringAfter("://")?.substringBefore('/')?.takeIf { it.isNotBlank() }
    return when (phase) {
        WorkshopLoadPhase.Connecting -> host?.let {
            stringResource(R.string.workshop_load_progress_node, it)
        }
        WorkshopLoadPhase.FailingOver -> {
            val reason = detail?.takeIf { it.isNotBlank() }
            when {
                host != null && reason != null ->
                    stringResource(R.string.workshop_load_progress_node_failed_reason, host, reason)
                host != null -> stringResource(R.string.workshop_load_progress_node_failed, host)
                else -> null
            }
        }
        WorkshopLoadPhase.FallingBack -> stringResource(R.string.workshop_load_progress_official_origin)
        WorkshopLoadPhase.Failed -> detail?.takeIf { it.isNotBlank() }
        else -> null
    }
}
