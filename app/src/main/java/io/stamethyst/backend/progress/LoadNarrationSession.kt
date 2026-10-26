package io.stamethyst.backend.progress

/**
 * One user-perceivable stage of a narrated load.
 *
 * Implemented by each surface's own phase enum so the narration rules (monotonic advance, recovery
 * rewinds, terminal latch) live in one place while the stages stay domain-specific.
 */
internal interface NarratedLoadPhase {
    /** True once no further update can arrive for the attempt. */
    val isTerminal: Boolean

    /**
     * A recovery that may happen *during* another stage, such as a node failover.
     *
     * Recoveries are exempt from the monotonic rule because they legitimately retell the step being
     * retried; they also lower the narration floor so the retried stage narrates again.
     */
    val isRecovery: Boolean
}

/** One forwarded progress snapshot of a narrated load. */
internal data class LoadNarrationUpdate<P>(
    val sessionId: Long,
    val phase: P,
    /** Node currently being used or retried, when the phase concerns a specific node. */
    val target: String? = null,
    /** How many distinct targets have failed during this attempt. */
    val failedTargetCount: Int = 0,
    /** Short technical reason for the latest failure, for diagnostics text. */
    val detail: String? = null,
)

/**
 * Session bookkeeping for a narratable load, shared by every surface that shows [LoadStageProgressBar].
 *
 * A pipeline narrates several sequential requests under one session, and each request re-emits the
 * same stages. A plain pass-through would therefore walk the bar backwards on every extra request,
 * so non-recovery phases are kept monotonic. Only the active session is forwarded, so a stale
 * in-flight request cannot rewind the bar of a newer one.
 *
 * Reporting may happen on OkHttp's own threads; the listener is invoked defensively and listeners
 * are expected to hop to their own dispatcher if they touch UI state.
 */
internal class LoadNarrationSession<P>(
    private val initialPhase: P,
    /** Stage a recovery rewinds the narration floor to, normally the retried stage. */
    private val recoveryFloor: P,
) where P : Enum<P>, P : NarratedLoadPhase {
    private val lock = Any()
    private var nextSessionId: Long = 0L
    private var activeSessionId: Long = 0L
    private var currentPhase: P? = null

    /** Lowest ordinal a non-recovery phase may still narrate; advances as phases are accepted. */
    private var narrationFloorOrdinal: Int = 0
    private var failedTargets: MutableSet<String> = linkedSetOf()
    private var listener: ((LoadNarrationUpdate<P>) -> Unit)? = null

    fun setListener(listener: ((LoadNarrationUpdate<P>) -> Unit)?) {
        synchronized(lock) {
            this.listener = listener
            if (listener == null) {
                activeSessionId = 0L
                currentPhase = null
                narrationFloorOrdinal = 0
                failedTargets = linkedSetOf()
            }
        }
    }

    /** Opens a new attempt and makes it the only one whose updates are forwarded. */
    fun beginSession(): Long {
        val sessionId: Long
        val target: ((LoadNarrationUpdate<P>) -> Unit)?
        synchronized(lock) {
            sessionId = ++nextSessionId
            activeSessionId = sessionId
            currentPhase = initialPhase
            narrationFloorOrdinal = initialPhase.ordinal
            failedTargets = linkedSetOf()
            target = listener
        }
        target?.dispatch(LoadNarrationUpdate(sessionId = sessionId, phase = initialPhase))
        return sessionId
    }

    fun report(
        sessionId: Long,
        phase: P,
        target: String? = null,
        detail: String? = null,
    ) {
        val consumer: ((LoadNarrationUpdate<P>) -> Unit)?
        val failureCount: Int
        synchronized(lock) {
            if (sessionId != activeSessionId) {
                return
            }
            if (currentPhase?.isTerminal == true && !phase.isTerminal) {
                return
            }
            if (!phase.isTerminal) {
                if (phase.isRecovery) {
                    narrationFloorOrdinal = minOf(narrationFloorOrdinal, recoveryFloor.ordinal)
                } else {
                    if (phase.ordinal < narrationFloorOrdinal) {
                        return
                    }
                    narrationFloorOrdinal = phase.ordinal
                }
            }
            currentPhase = phase
            if (target != null && phase.isRecovery) {
                failedTargets += target
            }
            failureCount = failedTargets.size
            consumer = listener
        }
        consumer?.dispatch(
            LoadNarrationUpdate(
                sessionId = sessionId,
                phase = phase,
                target = target,
                failedTargetCount = failureCount,
                detail = detail,
            ),
        )
    }

    /** Ends the attempt so late events from finished requests are ignored. */
    fun endSession(sessionId: Long) {
        synchronized(lock) {
            if (sessionId == activeSessionId) {
                activeSessionId = 0L
                currentPhase = null
                narrationFloorOrdinal = 0
                failedTargets = linkedSetOf()
            }
        }
    }

    private fun ((LoadNarrationUpdate<P>) -> Unit).dispatch(update: LoadNarrationUpdate<P>) {
        runCatching { this(update) }
    }
}
