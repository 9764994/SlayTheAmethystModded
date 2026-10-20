package io.stamethyst.ui.aimod

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.backend.llm.AgentCacheStats
import io.stamethyst.backend.llm.AgentCacheUsage
import io.stamethyst.backend.llm.AgentContextState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.NumberFormat

/** Isolated dialog fixture. Does not access AI credentials, Mod data or network. */
@RunWith(AndroidJUnit4::class)
class AiContextCacheInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dialogShowsMeasuredCacheRatesAndUpdatesUnreportedToUnknown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "cache-ui-fixture"
        val stats = AgentCacheStats().observe(AgentCacheUsage(1000, 800, null))
        val state = mutableStateOf(AgentContextState(modelKey = key, cacheStats = stats))
        compose.setContent {
            MaterialTheme {
                AiContextCounter(tokens = 1000, modelKey = key, busy = false, contextState = state.value)
            }
        }
        val label = context.getString(R.string.ai_mod_editor_context)
        compose.onNodeWithContentDescription("$label: ${NumberFormat.getIntegerInstance().format(1000)}").performClick()
        compose.onNodeWithText(context.getString(R.string.ai_mod_editor_cache_rates,
            formatAiCacheRate(0.8), formatAiCacheRate(0.8))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_mod_editor_cache_tokens,
            NumberFormat.getIntegerInstance().format(800), NumberFormat.getIntegerInstance().format(1000), 1L, 1L))
            .assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(cacheStats = stats.observe(AgentCacheUsage(1500, null, null))) }
        compose.onNodeWithText(context.getString(R.string.ai_mod_editor_cache_rates,
            "--", formatAiCacheRate(0.8))).assertIsDisplayed()
    }

    @Test fun modelMismatchDoesNotShowPreviousModelsCacheRate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                AiContextCounter(tokens = null, modelKey = "new-model", busy = false,
                    contextState = AgentContextState(modelKey = "old-model",
                        cacheStats = AgentCacheStats().observe(AgentCacheUsage(1000, 1000, null))))
            }
        }
        val label = context.getString(R.string.ai_mod_editor_context)
        val unknown = context.getString(R.string.ai_mod_editor_context_unknown)
        compose.onNodeWithContentDescription("$label: $unknown").performClick()
        compose.onNodeWithText(context.getString(R.string.ai_mod_editor_cache_rates, "--", "--")).assertIsDisplayed()
    }

    @Test fun changingEndpointDoesNotShowAnOldGatewaysAccountingConvention() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                AiContextCounter(tokens = null, modelKey = "same-model", busy = false, endpoint = "RESPONSES",
                    contextState = AgentContextState(modelKey = "same-model",
                        cacheStats = AgentCacheStats(endpoint = "CHAT_COMPLETIONS")
                            .observe(AgentCacheUsage(1000, 1000, null))))
            }
        }
        val label = context.getString(R.string.ai_mod_editor_context)
        val unknown = context.getString(R.string.ai_mod_editor_context_unknown)
        compose.onNodeWithContentDescription("$label: $unknown").performClick()
        compose.onNodeWithText(context.getString(R.string.ai_mod_editor_cache_rates, "--", "--")).assertIsDisplayed()
    }
}
