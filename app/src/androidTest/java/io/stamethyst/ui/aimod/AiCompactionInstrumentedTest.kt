package io.stamethyst.ui.aimod

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.backend.llm.AgentCompactionProgress
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No credentials, providers or stored conversations involved. */
@RunWith(AndroidJUnit4::class)
class AiCompactionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun timelineShowsStartRunningAndCompletionUsage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val event = mutableStateOf(AgentCompactionProgress(id = "fixture"))
        compose.setContent { MaterialTheme { AiCompactionPart(Json.encodeToString(event.value), streaming = true) } }
        compose.onNodeWithText(context.getString(R.string.ai_compaction_started)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_compaction_running)).assertIsDisplayed()

        compose.runOnIdle { event.value = event.value.copy(status = "completed", summary = "## Objective\nContinue safely",
            inputTokens = 1000, outputTokens = 200) }

        compose.onNodeWithText(context.getString(R.string.ai_compaction_running)).assertDoesNotExist()
        val usage = context.getString(R.string.ai_compaction_usage, formatAiTokenCount(1000), formatAiTokenCount(200))
        compose.onNodeWithText(context.getString(R.string.ai_compaction_completed) + " · " + usage).assertIsDisplayed()
    }

    @Test fun persistedRunningCompactionBecomesInterruptedWithoutSpinner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme {
            AiCompactionPart(Json.encodeToString(AgentCompactionProgress(id = "stale", summary = "partial")), streaming = false)
        } }
        compose.onNodeWithText(context.getString(R.string.ai_compaction_interrupted)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_compaction_running)).assertDoesNotExist()
        compose.onNodeWithText("partial").assertDoesNotExist()
    }

    @Test fun failedCompactionShowsErrorButNotInvalidSummary() {
        compose.setContent { MaterialTheme {
            AiCompactionPart(Json.encodeToString(AgentCompactionProgress(id = "failed", status = "failed",
                summary = "invalid summary", error = "Compaction produced no summary.")), streaming = false)
        } }
        compose.onNodeWithText("Compaction produced no summary.").assertIsDisplayed()
        compose.onNodeWithText("invalid summary").assertDoesNotExist()
    }
}
