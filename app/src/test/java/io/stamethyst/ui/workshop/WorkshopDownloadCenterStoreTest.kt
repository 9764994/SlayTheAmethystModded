package io.stamethyst.ui.workshop

import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WorkshopDownloadCenterStoreTest {
    @Before
    fun setUp() {
        WorkshopDownloadCenterStore.replaceInMemory(emptyList())
    }

    @After
    fun tearDown() {
        WorkshopDownloadCenterStore.replaceInMemory(emptyList())
    }

    @Test
    fun loadedTasksAreOrderedByMostRecentUpdate() {
        WorkshopDownloadCenterStore.replaceInMemory(listOf(task(1u, 100), task(2u, 300), task(3u, 200)))

        assertOrder(2u, 3u, 1u)
    }

    @Test
    fun restartingExistingTaskMovesItToTopWithoutDuplicatingIt() {
        WorkshopDownloadCenterStore.replaceInMemory(listOf(task(1u, 100), task(2u, 200)))

        WorkshopDownloadCenterStore.upsertInMemory(task(1u, 300, WorkshopDownloadTaskStatus.Queued))

        assertOrder(1u, 2u)
        assertEquals(WorkshopDownloadTaskStatus.Queued, WorkshopDownloadCenterStore.taskStatuses[1uL])
    }

    @Test
    fun statusUpdateMovesTaskToTop() {
        WorkshopDownloadCenterStore.replaceInMemory(listOf(task(1u, 100), task(2u, 200)))

        WorkshopDownloadCenterStore.updateInMemory(1u) {
            it.copy(status = WorkshopDownloadTaskStatus.Completed, updatedAtMillis = 300)
        }

        assertOrder(1u, 2u)
        assertEquals(WorkshopDownloadTaskStatus.Completed, WorkshopDownloadCenterStore.taskStatuses[1uL])
    }

    @Test
    fun olderTaskDoesNotDisplaceMoreRecentlyUpdatedTask() {
        WorkshopDownloadCenterStore.upsertInMemory(task(1u, 300))
        WorkshopDownloadCenterStore.upsertInMemory(task(2u, 100))
        WorkshopDownloadCenterStore.upsertInMemory(task(3u, 200))

        assertOrder(1u, 3u, 2u)
    }

    @Test
    fun launcherRefreshKeepsFinishedTasksInRecencyOrder() {
        WorkshopDownloadCenterStore.replaceInMemory(
            listOf(task(1u, 300, WorkshopDownloadTaskStatus.Completed), task(2u, 100)),
        )

        WorkshopDownloadCenterStore.replaceLauncherCardTasksInMemory(listOf(task(2u, 200)))

        assertOrder(1u, 2u)
    }

    @Test
    fun displayOrderDoesNotChangeQueuedTaskExecutionOrder() {
        WorkshopDownloadCenterStore.replaceInMemory(
            listOf(task(1u, 100, WorkshopDownloadTaskStatus.Queued), task(2u, 200, WorkshopDownloadTaskStatus.Queued)),
        )

        assertOrder(2u, 1u)
        assertEquals(1uL, WorkshopDownloadCenterStore.nextQueuedTask()?.publishedFileId)
    }

    private fun assertOrder(vararg ids: ULong) {
        assertEquals(ids.toList(), WorkshopDownloadCenterStore.tasks.map { it.publishedFileId })
    }

    private fun task(
        id: ULong,
        updatedAtMillis: Long,
        status: WorkshopDownloadTaskStatus = WorkshopDownloadTaskStatus.Downloading,
    ): WorkshopDownloadTaskUi = WorkshopDownloadTaskUi(
        publishedFileId = id,
        title = "Task $id",
        status = status,
        message = "",
        updatedAtMillis = updatedAtMillis,
        details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                appId = 646570u,
                publishedFileId = id,
                title = "Task $id",
                previewUrl = "",
                description = "",
            ),
        ),
    )
}
