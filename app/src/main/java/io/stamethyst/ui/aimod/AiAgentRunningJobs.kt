package io.stamethyst.ui.aimod

import io.stamethyst.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local reservations, including jobs still starting their foreground service. */
internal class AiAgentRunningJobs {
    private val jobs = linkedMapOf<String, Route.AiModEditor?>()
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()
    private val _activeEditorRoute = MutableStateFlow<Route.AiModEditor?>(null)
    val activeEditorRoute = _activeEditorRoute.asStateFlow()

    @Synchronized
    fun contains(jobId: String): Boolean = jobId in jobs

    @Synchronized
    fun reserve(jobId: String): Boolean {
        if (jobs.isNotEmpty()) return false
        return add(jobId)
    }

    @Synchronized
    fun add(jobId: String, editorRoute: Route.AiModEditor? = null): Boolean {
        val added = jobId !in jobs
        jobs[jobId] = editorRoute ?: jobs[jobId]
        publishState()
        return added
    }

    @Synchronized
    fun remove(jobId: String): Boolean {
        val removed = jobs.containsKey(jobId)
        jobs.remove(jobId)
        publishState()
        return removed
    }

    private fun publishState() {
        _activeEditorRoute.value = jobs.values.firstOrNull { it != null }
        _isRunning.value = jobs.isNotEmpty()
    }
}
