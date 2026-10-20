package io.stamethyst.ui.aimod

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiThinkingExpansionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thinkingBlocksExpandIndependentlyAcrossStreamingUpdatesAndRestoration() {
        val first = AiMessagePart(AiMessagePartKind.THINKING, "First preview\nFirst details")
        val second = AiMessagePart(AiMessagePartKind.THINKING, "Second preview\nSecond details")
        val message = mutableStateOf(AiEditorMessage(
            id = 2,
            fromUser = false,
            text = "Between thoughts",
            streaming = true,
            parts = listOf(first, AiMessagePart(AiMessagePartKind.TEXT, "Between thoughts"), second),
        ))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                AiMessageCard(message.value, {}, {}, {}, {}, enabled = true)
            }
        }

        compose.onNodeWithText(first.content).assertDoesNotExist()
        compose.onNodeWithText(second.content).assertDoesNotExist()
        compose.onNodeWithText("First preview").performClick()
        compose.onNodeWithText(first.content).assertIsDisplayed()
        compose.onNodeWithText(second.content).assertDoesNotExist()

        val updatedSecond = second.copy(content = second.content + "\nMore details")
        compose.runOnIdle {
            message.value = message.value.copy(parts = message.value.parts.dropLast(1) + updatedSecond)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(first.content).assertIsDisplayed()
        compose.onNodeWithText(updatedSecond.content).assertDoesNotExist()

        compose.onNodeWithText("Second preview").performClick()
        compose.onNodeWithText(first.content).assertIsDisplayed()
        compose.onNodeWithText(updatedSecond.content).assertIsDisplayed()
        compose.onNodeWithText("First preview").performClick()
        compose.onNodeWithText(first.content).assertDoesNotExist()
        compose.onNodeWithText(updatedSecond.content).assertIsDisplayed()
    }
}
