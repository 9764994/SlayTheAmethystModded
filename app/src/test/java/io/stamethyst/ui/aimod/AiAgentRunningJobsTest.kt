package io.stamethyst.ui.aimod

import io.stamethyst.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAgentRunningJobsTest {
    @Test fun activeRouteTracksTheRunningConversationAndClearsOnRelease() {
        val jobs = AiAgentRunningJobs()
        val route = Route.AiModEditor("/mods/example.jar", "Example", "example", "conversation-1")
        assertTrue(jobs.reserve("first"))
        assertNull(jobs.activeEditorRoute.value)
        jobs.add("first", route)
        assertEquals(route, jobs.activeEditorRoute.value)
        jobs.add("first")
        jobs.remove("unknown")
        assertEquals(route, jobs.activeEditorRoute.value)
        jobs.remove("first")
        assertNull(jobs.activeEditorRoute.value)
    }

    @Test fun removingAnotherJobPreservesTheActiveRoute() {
        val jobs = AiAgentRunningJobs()
        val first = Route.AiModEditor("/mods/first.jar", "First", "first", "conversation-1")
        val second = Route.AiModEditor("/mods/second.jar", "Second", "second", "conversation-2")
        jobs.add("first", first)
        jobs.add("second", second)
        jobs.remove("first")
        assertEquals(second, jobs.activeEditorRoute.value)
        jobs.remove("second")
        assertNull(jobs.activeEditorRoute.value)
    }

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
