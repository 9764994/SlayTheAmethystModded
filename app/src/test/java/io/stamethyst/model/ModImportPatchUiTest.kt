package io.stamethyst.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModImportPatchUiTest {
    private val mod = ModItemUi(
        modId = "test", manifestModId = "test", storagePath = "/test.jar",
        name = "Test", version = "1", description = "", dependencies = emptyList(),
        required = false, installed = true, enabled = true,
        explicitPriority = null, effectivePriority = null
    )
    private val patch = ModImportPatchUi(
        moduleId = "test.patch", name = "Patch", summary = "", appliedVersion = null,
        currentVersion = 2, userConfigurable = true, enabled = true
    )

    @Test
    fun missingRecord_enablesReimportEvenWithNoOutdatedPatches() {
        assertTrue(mod.copy(importPatches = listOf(patch)).needsImportPatchUpdate)
    }

    @Test
    fun disabledMissingPatch_stillShowsItsStatusAndAllowsReimport() {
        assertTrue(mod.copy(importPatches = listOf(patch.copy(enabled = false))).needsImportPatchUpdate)
    }

    @Test
    fun currentOrAbsentPatches_doNotRequireReimport() {
        assertFalse(mod.needsImportPatchUpdate)
        assertFalse(mod.copy(importPatches = listOf(patch.copy(appliedVersion = 2))).needsImportPatchUpdate)
    }

    @Test
    fun outdatedPatches_stillRequireReimport() {
        assertTrue(mod.copy(importPatches = listOf(patch.copy(appliedVersion = 1, isOutdated = true))).needsImportPatchUpdate)
        assertTrue(mod.copy(hasOutdatedImportPatches = true).needsImportPatchUpdate)
    }
}
