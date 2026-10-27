package io.stamethyst.ui.aimod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAgentRunningJobsTest {
    @Test fun reservationIsVisibleUntilReleased() {
        val jobs = AiAgentRunningJobs()
        assertFalse(jobs.isRunning.value)
        assertTrue(jobs.reserve("first"))
        assertTrue(jobs.contains("first"))
        assertTrue(jobs.isRunning.value)
        assertFalse(jobs.reserve("second"))
        jobs.remove("first")
        assertFalse(jobs.isRunning.value)
        assertTrue(jobs.reserve("second"))
    }

    @Test fun repeatedStartAndReleaseDoNotLeaveStaleState() {
        val jobs = AiAgentRunningJobs()
        jobs.reserve("first")
        jobs.add("first")
        jobs.remove("unknown")
        assertTrue(jobs.isRunning.value)
        jobs.remove("first")
        jobs.remove("first")
        assertFalse(jobs.isRunning.value)
    }

    @Test fun removingOneJobDoesNotHideAnotherActiveJob() {
        val jobs = AiAgentRunningJobs()
        jobs.add("first")
        jobs.add("second")
        jobs.remove("second")
        assertTrue(jobs.isRunning.value)
        jobs.remove("first")
        assertFalse(jobs.isRunning.value)
    }
}
