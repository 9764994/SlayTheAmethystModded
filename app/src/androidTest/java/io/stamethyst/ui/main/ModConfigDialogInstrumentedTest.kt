package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** All file IO is confined to a test-owned cache directory, never the user's mod settings. */
@RunWith(AndroidJUnit4::class)
class ModConfigDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporaryFolder = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val mod = ModItemUi(
        modId = "fixture", manifestModId = "fixture", storagePath = "fixture.jar", name = "Fixture",
        version = "1", description = "", dependencies = emptyList(), required = false,
        installed = true, enabled = true, explicitPriority = null, effectivePriority = null,
    )

    private fun fixture(): Pair<File, File> {
        val root = temporaryFolder.newFolder()
        val file = File(root, "fixture/config.properties").apply {
            parentFile!!.mkdirs()
            writeText("# fixture\nflag=false\nname=original\n")
        }
        return root to file
    }

    private fun openFile() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(context.getString(R.string.mod_config_manual_select)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.mod_config_manual_select)).performClick()
        scrollTo("mod-config-file:fixture/config.properties")
        compose.onNodeWithTag("mod-config-file:fixture/config.properties").performClick()
        compose.onNodeWithText(context.getString(R.string.mod_config_associate_confirm)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("mod-config-save"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        scrollTo("mod-config-entry:flag")
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("mod-config-list").performScrollToNode(hasTestTag(tag))
    }

    @Test fun editsBooleanAndTextAndSavesWithBackup() {
        val (root, file) = fixture()
        val original = file.readText()
        compose.setContent { MaterialTheme { ModConfigDialog(mod, true, {}, root) } }
        openFile()
        compose.onNodeWithTag("mod-config-entry:flag").performClick()
        scrollTo("mod-config-entry:name")
        compose.onNodeWithTag("mod-config-entry:name").performTextReplacement("中文 value")
        compose.onNodeWithTag("mod-config-save").performClick()
        compose.waitUntil(10_000) { File(file.path + ".bak").isFile && file.readText() != original }
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        assertEquals("true", properties.getProperty("flag"))
        assertEquals("中文 value", properties.getProperty("name"))
        assertEquals(original, File(file.path + ".bak").readText())
        compose.onNodeWithTag("mod-config-list").performScrollToNode(hasText(context.getString(R.string.mod_config_saved)))
        compose.onNodeWithText(context.getString(R.string.mod_config_saved)).assertIsDisplayed()
    }

    @Test fun closingOrSwitchingFileRequiresDiscardConfirmation() {
        val (root, file) = fixture()
        val original = file.readText()
        var dismissed = false
        compose.setContent { MaterialTheme { ModConfigDialog(mod, true, { dismissed = true }, root) } }
        openFile()
        compose.onNodeWithTag("mod-config-entry:flag").performClick()
        compose.onNodeWithText(context.getString(R.string.mod_config_close)).performClick()
        compose.onNodeWithText(context.getString(R.string.mod_config_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mod_config_keep_editing)).performClick()
        assertFalse(dismissed)
        compose.onNodeWithTag("mod-config-list").performScrollToNode(hasText(context.getString(R.string.mod_config_change_file)))
        compose.onNodeWithText(context.getString(R.string.mod_config_change_file)).performClick()
        compose.onNodeWithText(context.getString(R.string.mod_config_discard)).performClick()
        scrollTo("mod-config-file:fixture/config.properties")
        compose.onNodeWithTag("mod-config-file:fixture/config.properties").assertExists()
        assertEquals(original, file.readText())
        assertFalse(File(file.path + ".bak").exists())
    }

    @Test fun runningGameStateDisablesEditingAndSavingEvenAfterOpening() {
        val (root, file) = fixture()
        val enabled = mutableStateOf(true)
        compose.setContent { MaterialTheme { ModConfigDialog(mod, enabled.value, {}, root) } }
        openFile()
        compose.onNodeWithTag("mod-config-entry:flag").performClick()
        compose.runOnIdle { enabled.value = false }
        scrollTo("mod-config-entry:flag")
        compose.onNodeWithTag("mod-config-entry:flag").assertIsNotEnabled()
        scrollTo("mod-config-entry:name")
        compose.onNodeWithTag("mod-config-entry:name").assertIsNotEnabled()
        compose.onNodeWithTag("mod-config-save").assertIsNotEnabled()
        assertTrue(file.readText().contains("flag=false"))
    }

    @Test fun emptyDirectoryExplainsHowToGenerateSettings() {
        val root = temporaryFolder.newFolder()
        compose.setContent { MaterialTheme { ModConfigDialog(mod, true, {}, root) } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(context.getString(R.string.mod_config_manual_select)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("mod-config-list").performScrollToNode(hasText(context.getString(R.string.mod_config_empty)))
        compose.onNodeWithText(context.getString(R.string.mod_config_empty)).assertIsDisplayed()
    }

    @Test fun threeDotActionsHideConfiguration() {
        compose.setContent { MaterialTheme {
            ModActionsDialog(
                visible = true, controlsEnabled = true, onDismiss = {}, favorite = false,
                onFavoriteChange = {}, onEditPriority = {},
                onAssociate = {}, onExport = {}, onShare = {}, onRename = {}, onDelete = {},
            )
        } }
        compose.onNodeWithText(context.getString(R.string.mod_config_title)).assertDoesNotExist()
    }
}
