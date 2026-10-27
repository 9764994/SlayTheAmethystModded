package io.stamethyst.backend.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmReasoningEffortTest {
    @Test
    fun selectableTiersMatchApiValues() {
        assertEquals(listOf("low", "high", "max"), LlmReasoningEffort.selectable.map { it.name.lowercase() })
    }

    @Test
    fun legacyAndMissingPreferencesUseHigh() {
        listOf(null, "OFF", "MEDIUM", "HIGH", "unknown").forEach {
            assertEquals(LlmReasoningEffort.HIGH, LlmReasoningEffort.fromStored(it))
        }
        assertEquals(LlmReasoningEffort.LOW, LlmReasoningEffort.fromStored("LOW"))
        assertEquals(LlmReasoningEffort.MAX, LlmReasoningEffort.fromStored("MAX"))
        assertEquals(LlmReasoningEffort.HIGH, LlmSettings().reasoningEffort)
    }
}
