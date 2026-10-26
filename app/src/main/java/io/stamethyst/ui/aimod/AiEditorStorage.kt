package io.stamethyst.ui.aimod

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Queued writes outlive a navigation entry; a newly opened editor reads after those writes. */
internal object AiEditorStorage {
    val dispatcher = Dispatchers.IO.limitedParallelism(1)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
}
