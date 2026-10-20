package io.stamethyst.ui

import android.view.WindowManager
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class LauncherDialogMotionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun windowAnimationsAndSystemDimmingAreDisabled() {
        var window: android.view.Window? = null
        compose.setContent {
            LauncherDialogHost {
                LauncherDialog(onDismissRequest = {}) {
                    val view = LocalView.current
                    SideEffect {
                        window = (view.parent as DialogWindowProvider).window
                    }
                    Text("Dialog content")
                }
            }
        }
        compose.runOnIdle {
            val attributes = requireNotNull(window).attributes
            assertEquals(0, attributes.windowAnimations)
            assertFalse(attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
        }
    }

    @Test fun removingCallerRetainsContentUntilExitCompletes() {
        val visible = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LauncherDialogHost {
                if (visible.value) LauncherAlertDialog(
                    onDismissRequest = { visible.value = false },
                    title = { Text("Retained dialog") },
                    confirmButton = {
                        TextButton(onClick = { visible.value = false }) { Text("Close") }
                    },
                )
            }
        }
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("Close").performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Retained dialog").assertExists()
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithText("Retained dialog").assertDoesNotExist()
    }

    @Test fun nestedDialogCanCloseWithoutRemovingItsParent() {
        val child = mutableStateOf(true)
        compose.setContent {
            LauncherDialogHost {
                LauncherDialog(onDismissRequest = {}) {
                    Text("Parent")
                    if (child.value) LauncherAlertDialog(
                        onDismissRequest = { child.value = false },
                        title = { Text("Child") },
                        confirmButton = { TextButton(onClick = { child.value = false }) { Text("Close child") } },
                    )
                }
            }
        }
        compose.onNodeWithText("Close child").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Child").assertDoesNotExist()
        compose.onNodeWithText("Parent").assertExists()
    }
}
