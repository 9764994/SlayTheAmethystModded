package io.stamethyst.backend.mods.importing.patches

import io.stamethyst.backend.mods.ImportedModPatchInfo
import io.stamethyst.backend.mods.ImportedModPatchRecord
import io.stamethyst.backend.mods.importing.patches.mods.firstperson.FirstPersonViewImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.frieren.FrierenImportPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasFilterPatchModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledModImportPatchesTest {
    @Test
    fun legacyModWithoutRecords_includesItsTargetedPatch() {
        assertEquals(
            listOf(FrierenImportPatchModule),
            ImportPatchRegistry.modulesForInstalledMod("frierenmod")
        )
    }

    @Test
    fun allDedicatedPatches_areDiscoverableWithoutHistory() {
        val targets = listOf(
            "frierenmod", "downfall", "firstperson", "vupshionmod", "jacketnoanokomod",
            "another ori mod", "chaofanmod", "superfastmode", "anniv6"
        )
        // Verify target discovery without coupling this test to individual module identifiers.
        targets.forEach { target ->
            val modules = ImportPatchRegistry.modulesForInstalledMod(target)
            assertEquals(target, 1, modules.size)
            assertEquals(target, modules.single().targetModId)
        }
    }

    @Test
    fun unrelatedModWithoutRecords_doesNotInferGenericOrOtherModPatches() {
        assertTrue(ImportPatchRegistry.modulesForInstalledMod("unrelated").isEmpty())
        assertTrue(ImportPatchRegistry.modulesForInstalledMod("").isEmpty())
    }

    @Test
    fun newlyAddedPatch_isIncludedAlongsideExistingGenericRecord() {
        val info = ImportedModPatchInfo(
            modId = "frierenmod",
            modName = "Frieren",
            appliedPatches = listOf(ImportedModPatchRecord(AtlasFilterPatchModule.id, 1))
        )
        assertEquals(
            setOf(AtlasFilterPatchModule.id, FrierenImportPatchModule.id),
            ImportPatchRegistry.modulesForInstalledMod("frierenmod", info).map { it.id }.toSet()
        )
    }

    @Test
    fun recordedTargetedPatch_isNotDuplicatedAndKeepsItsVersion() {
        val module = FirstPersonViewImportPatchModule
        val info = ImportedModPatchInfo(
            modId = "firstperson",
            modName = "First Person",
            appliedPatches = listOf(ImportedModPatchRecord(module.id, 1))
        )
        assertEquals(listOf(module), ImportPatchRegistry.modulesForInstalledMod("firstperson", info))
        assertEquals(1, info.appliedPatchVersion(module.id))
        assertTrue(ImportPatchRegistry.hasOutdatedAppliedPatches(info))
        assertFalse(ImportPatchRegistry.hasOutdatedAppliedPatches(info.copy(
            appliedPatches = listOf(ImportedModPatchRecord(module.id, module.version))
        )))
    }
}
