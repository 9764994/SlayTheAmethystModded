package io.stamethyst.ui.aimod

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local reservations, including jobs still starting their foreground service. */
internal class AiAgentRunningJobs {
    private val jobs = mutableSetOf<String>()
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    @Synchronized
    fun contains(jobId: String): Boolean = jobId in jobs

    @Synchronized
    fun reserve(jobId: String): Boolean {
        if (jobs.isNotEmpty()) return false
        return add(jobId)
    }

    @Synchronized
    fun add(jobId: String): Boolean {
        val added = jobs.add(jobId)
        _isRunning.value = jobs.isNotEmpty()
        return added
    }

    @Synchronized
    fun remove(jobId: String): Boolean {
        val removed = jobs.remove(jobId)
        _isRunning.value = jobs.isNotEmpty()
        return removed
    }
}
