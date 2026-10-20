package io.stamethyst.ui.main

import io.stamethyst.backend.mods.AgentPatchUploadProgress
import io.stamethyst.backend.mods.AgentPatchUploadStage
import kotlinx.coroutines.delay

internal val AGENT_PATCH_UPLOAD_CHECKPOINTS = listOf(
    AgentPatchUploadStage.PREPARING,
    AgentPatchUploadStage.PACKAGING_SOURCES,
    AgentPatchUploadStage.UPLOADING,
)

/** Pace presentation, not network work. Even conflated/skipped stages get a visible loading state. */
internal class AgentPatchUploadProgressPresenter(
    private val show: (AgentPatchUploadProgress) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) {
    private var currentIndex = 0
    private var stageStartedAt = nowMillis()

    suspend fun update(value: AgentPatchUploadProgress) {
        // Waiting for the HTTP response is still part of uploading, not a Git commit check.
        val progress = if (value.stage == AgentPatchUploadStage.PROCESSING) {
            value.copy(stage = AgentPatchUploadStage.UPLOADING)
        } else value
        val targetIndex = if (progress.stage == AgentPatchUploadStage.DONE) {
            AGENT_PATCH_UPLOAD_CHECKPOINTS.size
        } else AGENT_PATCH_UPLOAD_CHECKPOINTS.indexOf(progress.stage)
        if (targetIndex < currentIndex) return
        while (currentIndex < targetIndex) {
            val remaining = 500L - (nowMillis() - stageStartedAt)
            if (remaining > 0) waitMillis(remaining)
            currentIndex++
            stageStartedAt = nowMillis()
            if (currentIndex < targetIndex) {
                val stage = AGENT_PATCH_UPLOAD_CHECKPOINTS[currentIndex]
                show(AgentPatchUploadProgress(stage, if (currentIndex == 1) 0.03f else 0.1f))
            }
        }
        show(progress)
    }
}
