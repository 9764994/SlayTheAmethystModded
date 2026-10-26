package io.stamethyst.backend.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadNarrationSessionTest {
    private enum class Phase : NarratedLoadPhase {
        Preparing,
        Connecting,
        Parsing,
        FailingOver,
        FallingBack,
        Completed,
        Failed,
        ;

        override val isTerminal: Boolean
            get() = this == Completed || this == Failed

        override val isRecovery: Boolean
            get() = this == FailingOver || this == FallingBack
    }

    private fun session() = LoadNarrationSession<Phase>(
        initialPhase = Phase.Preparing,
        recoveryFloor = Phase.Connecting,
    )

    @Test
    fun laterRequestsInOneSessionDoNotRewindTheBar() {
        val session = session()
        val received = mutableListOf<Phase>()
        session.setListener { received += it.phase }
        val id = session.beginSession()
        session.report(id, Phase.Connecting)
        session.report(id, Phase.Parsing)
        // A second request under the same session starts over at Connecting; it must not rewind.
        session.report(id, Phase.Connecting)
        session.report(id, Phase.Parsing)

        assertEquals(
            listOf(Phase.Preparing, Phase.Connecting, Phase.Parsing, Phase.Parsing),
            received,
        )
    }

    @Test
    fun recoveryLowersTheFloorSoRetriedStageNarratesAgain() {
        val session = session()
        val received = mutableListOf<LoadNarrationUpdate<Phase>>()
        session.setListener { received += it }
        val id = session.beginSession()
        session.report(id, Phase.Connecting)
        session.report(id, Phase.Parsing)
        session.report(id, Phase.FailingOver, target = "node-a", detail = "timeout")
        session.report(id, Phase.Connecting, target = "node-b")

        assertEquals(
            listOf(
                Phase.Preparing,
                Phase.Connecting,
                Phase.Parsing,
                Phase.FailingOver,
                Phase.Connecting,
            ),
            received.map { it.phase },
        )
        val failover = received.single { it.phase == Phase.FailingOver }
        assertEquals(1, failover.failedTargetCount)
        assertEquals("node-a", failover.target)
        assertEquals("timeout", failover.detail)
    }

    @Test
    fun fallbackCountsTheFailedTargetWithoutRewinding() {
        val session = session()
        val received = mutableListOf<LoadNarrationUpdate<Phase>>()
        session.setListener { received += it }
        val id = session.beginSession()
        session.report(id, Phase.FailingOver, target = "node-a")
        session.report(id, Phase.FallingBack, target = "node-b")
        session.report(id, Phase.Parsing)

        assertEquals(2, received.last().failedTargetCount)
        assertEquals(Phase.Parsing, received.last().phase)
    }

    @Test
    fun nonTerminalUpdatesAreDroppedAfterATerminalOne() {
        val session = session()
        val received = mutableListOf<Phase>()
        session.setListener { received += it.phase }
        val id = session.beginSession()
        session.report(id, Phase.Completed)
        session.report(id, Phase.Parsing)

        assertEquals(listOf(Phase.Preparing, Phase.Completed), received)
    }

    @Test
    fun staleSessionIsIgnoredAndANewOneResetsTheFloor() {
        val session = session()
        val received = mutableListOf<LoadNarrationUpdate<Phase>>()
        session.setListener { received += it }
        val superseded = session.beginSession()
        val active = session.beginSession()
        session.report(superseded, Phase.Completed)
        session.report(active, Phase.Connecting)

        assertEquals(
            listOf(Phase.Preparing, Phase.Preparing, Phase.Connecting),
            received.map { it.phase },
        )
        assertTrue(received.all { it.sessionId == superseded || it.sessionId == active })
    }

    @Test
    fun endingASessionIgnoresLateUpdates() {
        val session = session()
        val received = mutableListOf<Phase>()
        session.setListener { received += it.phase }
        val id = session.beginSession()
        session.endSession(id)
        session.report(id, Phase.Connecting)

        assertEquals(listOf(Phase.Preparing), received)
    }

    @Test
    fun aThrowingListenerCannotBreakReporting() {
        val session = session()
        session.setListener { error("listener must not propagate") }
        val id = session.beginSession()

        session.report(id, Phase.Connecting)
        session.report(id, Phase.Completed)
    }
}
