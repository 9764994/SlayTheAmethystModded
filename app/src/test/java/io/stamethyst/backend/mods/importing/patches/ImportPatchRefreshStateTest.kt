package io.stamethyst.backend.mods.importing.patches

import io.stamethyst.backend.mods.ImportedModPatchInfo
import io.stamethyst.backend.mods.ImportedModPatchRecord
import io.stamethyst.backend.mods.importing.patches.mods.frieren.FrierenImportPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasFilterPatchModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPatchRefreshStateTest {
    @Test
    fun sharedBaseStatesDoNotLeakAppliedVersionsBetweenMods() {
        val module = FrierenImportPatchModule
        val base = state(module, available = true)
        val states = mapOf(module.id to base)
        val info = ImportedModPatchInfo(
            modId = module.targetModId.orEmpty(), modName = "test",
            appliedPatches = listOf(ImportedModPatchRecord(module.id, module.version - 1)),
        )
        val outdated = ImportPatchRegistry.statesForInstalledMod(module.targetModId!!, info, states).single()
        assertTrue(outdated.outdated)
        assertEquals(module.version - 1, outdated.appliedVersion)
        val unapplied = ImportPatchRegistry.statesForInstalledMod(module.targetModId!!, null, states).single()
        assertFalse(unapplied.outdated)
        assertEquals(null, unapplied.appliedVersion)
        assertEquals(base, states[module.id])
        assertTrue(ImportPatchRegistry.statesForInstalledMod("unrelated", null, states).isEmpty())
    }

    @Test
    fun recordedGenericPatchesRemainVisibleEvenWhenUnavailable() {
        val module = AtlasFilterPatchModule
        val states = mapOf(module.id to state(module, available = false))
        val info = ImportedModPatchInfo(
            modId = "ordinary", modName = "ordinary",
            appliedPatches = listOf(ImportedModPatchRecord(module.id, module.version)),
        )
        assertTrue(ImportPatchRegistry.statesForInstalledMod("ordinary", null, states).isEmpty())
        val recorded = ImportPatchRegistry.statesForInstalledMod("ordinary", info, states).single()
        assertEquals(module.version, recorded.appliedVersion)
        assertFalse(recorded.outdated)
    }

    @Test
    fun unavailableUnappliedTargetPatchIsHiddenButRecordedVersionRemainsVisible() {
        val module = FrierenImportPatchModule
        val states = mapOf(module.id to state(module, available = false))
        assertTrue(ImportPatchRegistry.statesForInstalledMod(module.targetModId, null, states).isEmpty())
        val info = ImportedModPatchInfo(
            modId = module.targetModId, modName = "test",
            appliedPatches = listOf(ImportedModPatchRecord(module.id, module.version)),
        )
        val result = ImportPatchRegistry.statesForInstalledMod(module.targetModId, info, states).single()
        assertEquals(module.version, result.appliedVersion)
        assertFalse(result.outdated)
        assertTrue(result.enabled)
    }

    private fun state(module: ImportPatchModule, available: Boolean) = ImportPatchModuleState(
        id = module.id, displayName = "name", summary = "summary", category = module.category,
        version = module.version, defaultEnabled = module.defaultEnabled,
        userConfigurable = module.userConfigurable, enabled = true, available = available,
        appliedVersion = null, outdated = false,
    )
}
