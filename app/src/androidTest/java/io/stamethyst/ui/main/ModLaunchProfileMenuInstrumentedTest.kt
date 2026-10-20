package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModLaunchProfileMenuInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedProfilesAreBrowsableButReadOnlyUntilScanCompletes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scanComplete = mutableStateOf(false)
        val selected = ArrayList<String>()
        compose.setContent {
            MaterialTheme {
                ModLaunchProfileMenu(
                    profiles = listOf(
                        ModLaunchProfile("default", "Default", emptySet()),
                        ModLaunchProfile("saved", "Saved", setOf("not-scanned-yet.jar")),
                    ),
                    activeProfileId = "saved",
                    enabled = scanComplete.value,
                    menuEnabled = true,
                    onSelectProfile = { selected.add(it) },
                    onAddProfile = { error("Unexpected add") },
                    onRenameProfile = { _, _ -> error("Unexpected rename") },
                    onDeleteProfile = { error("Unexpected delete") },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_button, "Saved"))
            .assertIsEnabled().performClick()
        val saved = context.getString(R.string.main_mod_launch_profile_selected_item, "Saved")
        compose.onNodeWithText(saved).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.main_mod_launch_profile_add)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_launch_profile_rename, "Saved"))
            .assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_launch_profile_delete, "Saved"))
            .assertIsNotEnabled()
        compose.runOnIdle { scanComplete.value = true }
        compose.onNodeWithText(saved).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("saved"), selected) }
    }
}
