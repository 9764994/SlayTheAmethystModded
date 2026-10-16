package io.stamethyst.ui.settings.steamcloud

import io.stamethyst.backend.steamcloud.SteamCloudSyncDirection
import io.stamethyst.backend.steamcloud.SteamCloudSyncPhase
import io.stamethyst.backend.steamcloud.SteamCloudSyncProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamCloudPullProgressTest {
    private fun progress(completed: Int = 0, total: Int = 0, percent: Int? = null) =
        SteamCloudSyncProgress(
            SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
            SteamCloudSyncPhase.DOWNLOADING,
            completed, total, progressPercent = percent,
        )

    @Test fun unknownTotalUsesIndeterminateProgress() {
        assertNull(progress().pullProgressPercent())
    }

    @Test fun fileCountsProduceCurrentPhasePercentage() {
        assertEquals(37, progress(3, 8).pullProgressPercent())
        assertEquals(100, progress(8, 8).pullProgressPercent())
    }

    @Test fun explicitPercentageTakesPriorityAndIsClamped() {
        assertEquals(20, progress(3, 8, 20).pullProgressPercent())
        assertEquals(100, progress(percent = 150).pullProgressPercent())
        assertEquals(0, progress(percent = -1).pullProgressPercent())
    }

    @Test fun largeCountsDoNotOverflow() {
        assertEquals(100, progress(Int.MAX_VALUE, Int.MAX_VALUE).pullProgressPercent())
    }
}
