package io.stamethyst.ui.main

import io.stamethyst.backend.mods.AgentPatchUploadProgress
import io.stamethyst.backend.mods.AgentPatchUploadStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPatchUploadProgressPresenterTest {
    @Test
    fun instantUploadStillDisplaysEveryCheckpointForHalfASecond() = runBlocking {
        var now = 0L
        val shown = mutableListOf<Pair<AgentPatchUploadStage, Long>>()
        val presenter = AgentPatchUploadProgressPresenter(
            show = { shown += it.stage to now },
            nowMillis = { now },
            waitMillis = { now += it },
        )

        presenter.update(AgentPatchUploadProgress(AgentPatchUploadStage.DONE, 1f))

        assertEquals(
            listOf(
                AgentPatchUploadStage.PACKAGING_SOURCES to 500L,
                AgentPatchUploadStage.UPLOADING to 1000L,
                AgentPatchUploadStage.DONE to 1500L,
            ),
            shown,
        )
    }

    @Test
    fun slowStagesDoNotWaitAgainAndProcessingRemainsUploading() = runBlocking {
        var now = 0L
        val shown = mutableListOf<Pair<AgentPatchUploadStage, Long>>()
        val presenter = AgentPatchUploadProgressPresenter(
            show = { shown += it.stage to now },
            nowMillis = { now },
            waitMillis = { now += it },
        )

        now = 800L
        presenter.update(AgentPatchUploadProgress(AgentPatchUploadStage.PACKAGING_SOURCES, 0.03f))
        now = 1800L
        presenter.update(AgentPatchUploadProgress(AgentPatchUploadStage.UPLOADING, 0.1f))
        now = 1900L
        presenter.update(AgentPatchUploadProgress(AgentPatchUploadStage.PROCESSING, 0.85f))
        presenter.update(AgentPatchUploadProgress(AgentPatchUploadStage.DONE, 1f))

        assertEquals(
            listOf(
                AgentPatchUploadStage.PACKAGING_SOURCES to 800L,
                AgentPatchUploadStage.UPLOADING to 1800L,
                AgentPatchUploadStage.UPLOADING to 1900L,
                AgentPatchUploadStage.DONE to 2300L,
            ),
            shown,
        )
    }
}
