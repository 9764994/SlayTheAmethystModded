package io.stamethyst.ui.main

import io.stamethyst.model.ModImportPatchUi
import io.stamethyst.model.ModItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPatchUpdateIgnoreStoreTest {
    @Test
    fun collectUpdateMods_includesDisabledInstalledModsButNotMissingOrRequiredMods() {
        val shion = createMod(
            "/mods/Shion.jar",
            listOf(patch("mod.vupshion.startup_compat", 2, 4)),
        )
        val disabled = shion.copy(enabled = false)

        assertEquals(
            listOf(shion, disabled),
            ImportPatchUpdateIgnoreStore.collectUpdateMods(
                listOf(shion, disabled, shion.copy(installed = false), shion.copy(required = true)),
            ),
        )
    }

    @Test
    fun shionV2ToV4_isNotSuppressedByIgnoredV2OrV3() {
        val shion = createMod(
            "/mods/Shion.jar",
            listOf(patch("mod.vupshion.startup_compat", 2, 4)),
        )
        val current = ImportPatchUpdateIgnoreStore.collectUpdateKeys(listOf(shion))
        val ignored = setOf(2, 3).mapTo(mutableSetOf()) { version ->
            ImportPatchUpdateIgnoreStore.buildUpdateKey(
                shion.storagePath, "mod.vupshion.startup_compat", version,
            )
        }

        assertTrue(ImportPatchUpdateIgnoreStore.hasUnignoredUpdates(current, ignored))
        assertFalse(ImportPatchUpdateIgnoreStore.hasUnignoredUpdates(current, ignored + current))
    }

    @Test
    fun acknowledgedUpdates_doNotSuppressNewerPatchVersions() {
        val v4 = createMod(
            "/mods/Shion.jar",
            listOf(patch("mod.vupshion.startup_compat", 2, 4)),
        )
        val acknowledged = ImportPatchUpdateIgnoreStore.collectUpdateKeys(listOf(v4))
        val v5 = v4.copy(importPatches = listOf(patch("mod.vupshion.startup_compat", 2, 5)))

        assertFalse(ImportPatchUpdateIgnoreStore.hasUnignoredUpdates(acknowledged, acknowledged))
        assertTrue(
            ImportPatchUpdateIgnoreStore.hasUnignoredUpdates(
                ImportPatchUpdateIgnoreStore.collectUpdateKeys(listOf(v5)), acknowledged,
            ),
        )
    }

    @Test
    fun collectUpdateKeys_onlyIncludesOutdatedOrUnrecordedPatches() {
        val mod = createMod(
            storagePath = "/mods/Test.jar",
            patches = listOf(
                patch(moduleId = "texture.atlas_filter", appliedVersion = 1, currentVersion = 2),
                patch(moduleId = "mod.vupshion.startup_compat", appliedVersion = 3, currentVersion = 3),
                patch(moduleId = "structure.manifest_root", appliedVersion = null, currentVersion = 4),
            ),
        )

        val keys = ImportPatchUpdateIgnoreStore.collectUpdateKeys(listOf(mod))

        assertEquals(
            setOf(
                ImportPatchUpdateIgnoreStore.buildUpdateKey("/mods/Test.jar", "texture.atlas_filter", 2),
                ImportPatchUpdateIgnoreStore.buildUpdateKey("/mods/Test.jar", "structure.manifest_root", 4),
            ),
            keys,
        )
    }

    @Test
    fun collectUpdateKeys_ignoresModsWithoutPatchUpdates() {
        val mod = createMod(
            storagePath = "/mods/Test.jar",
            patches = listOf(
                patch(moduleId = "texture.atlas_filter", appliedVersion = 2, currentVersion = 2),
            ),
        )

        assertTrue(ImportPatchUpdateIgnoreStore.collectUpdateKeys(listOf(mod)).isEmpty())
    }

    @Test
    fun hasUnignoredUpdates_reportsNewPatchVersionAfterIgnoring() {
        val ignored = setOf(
            ImportPatchUpdateIgnoreStore.buildUpdateKey("/mods/Test.jar", "texture.atlas_filter", 2),
        )

        assertFalse(
            ImportPatchUpdateIgnoreStore.hasUnignoredUpdates(
                currentKeys = ignored,
                ignoredKeys = ignored,
            )
        )
        assertTrue(
            ImportPatchUpdateIgnoreStore.hasUnignoredUpdates(
                currentKeys = setOf(
                    ImportPatchUpdateIgnoreStore.buildUpdateKey("/mods/Test.jar", "texture.atlas_filter", 3),
                ),
                ignoredKeys = ignored,
            )
        )
        assertTrue(
            ImportPatchUpdateIgnoreStore.hasUnignoredUpdates(
                currentKeys = ignored + ImportPatchUpdateIgnoreStore.buildUpdateKey(
                    "/mods/Test.jar",
                    "structure.manifest_root",
                    1,
                ),
                ignoredKeys = ignored,
            )
        )
    }

    private fun createMod(
        storagePath: String,
        patches: List<ModImportPatchUi>,
    ): ModItemUi {
        return ModItemUi(
            modId = "test",
            manifestModId = "test",
            storagePath = storagePath,
            name = "Test",
            version = "1.0.0",
            description = "",
            dependencies = emptyList(),
            required = false,
            installed = true,
            enabled = true,
            explicitPriority = null,
            effectivePriority = null,
            importPatches = patches,
        )
    }

    private fun patch(
        moduleId: String,
        appliedVersion: Int?,
        currentVersion: Int,
    ): ModImportPatchUi {
        return ModImportPatchUi(
            moduleId = moduleId,
            name = moduleId,
            summary = "",
            appliedVersion = appliedVersion,
            currentVersion = currentVersion,
            userConfigurable = false,
            enabled = true,
            isOutdated = appliedVersion != null && appliedVersion < currentVersion,
        )
    }
}
