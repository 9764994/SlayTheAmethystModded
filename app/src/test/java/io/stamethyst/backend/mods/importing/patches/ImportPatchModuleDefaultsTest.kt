package io.stamethyst.backend.mods.importing.patches

import io.stamethyst.backend.mods.ImportedModPatchInfo
import io.stamethyst.backend.mods.ImportedModPatchRecord
import io.stamethyst.backend.mods.importing.patches.mods.chaofanmod.ChaofanModImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.downfall.DownfallImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.frieren.FrierenImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.firstperson.FirstPersonViewImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.jacketnoanoko.JacketNoAnoKoImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.ori.OriImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.spirebiomes.SpireBiomesImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.vupshion.VupShionImportPatchModule
import io.stamethyst.backend.mods.importing.patches.structure.DuplicateZipEntryPatchModule
import io.stamethyst.backend.mods.importing.patches.structure.ManifestRootPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasFilterPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasOfflineDownscalePatchModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPatchModuleDefaultsTest {
    @Test
    fun spireBiomesFogPatch_isRegisteredAndUsesVersionedMetadata() {
        val module = SpireBiomesImportPatchModule
        assertSame(module, ImportPatchRegistry.moduleForId(module.id))
        assertEquals(1, ImportPatchRegistry.currentVersion(module.id))
        val patchInfo = ImportedModPatchInfo(
            modId = "anniv6",
            modName = "Spire Biomes",
            appliedPatches = listOf(ImportedModPatchRecord(module.id, module.version))
        )
        assertTrue(patchInfo.hasCompatibilityPatches)
        assertEquals(module.version, patchInfo.appliedPatchVersion(module.id))
        assertFalse(ImportPatchRegistry.hasOutdatedAppliedPatches(patchInfo))
    }

    @Test
    fun basePlan_usesModuleDefaultEnablement() {
        val atlasDownscalePlan = AtlasOfflineDownscalePatchModule.basePlan(applicable = true)
        assertFalse(atlasDownscalePlan.defaultEnabled)
        assertTrue(atlasDownscalePlan.userConfigurable)

        val duplicateEntryPlan = DuplicateZipEntryPatchModule.basePlan(applicable = true)
        assertTrue(duplicateEntryPlan.defaultEnabled)
        assertTrue(duplicateEntryPlan.userConfigurable)
    }

    @Test
    fun everyImportPatchModule_isUserConfigurableAndUsesDeclaredVersion() {
        val modules = allModules()

        assertTrue(modules.all { it.userConfigurable })
        assertTrue(modules.all { it.version >= 1 })
        assertEquals(2, FirstPersonViewImportPatchModule.version)
    }

    @Test
    fun onlyAtlasDownscale_isDisabledByDefault() {
        val modules = allModules()

        assertEquals(
            listOf(AtlasOfflineDownscalePatchModule.id),
            modules.filterNot { it.defaultEnabled }.map { it.id }
        )
        assertTrue(modules.filter { it.defaultEnabled }.all { it.userConfigurable })
    }

    private fun allModules() = listOf(
        DuplicateZipEntryPatchModule,
        ManifestRootPatchModule,
        AtlasFilterPatchModule,
        AtlasOfflineDownscalePatchModule,
        FrierenImportPatchModule,
        DownfallImportPatchModule,
        FirstPersonViewImportPatchModule,
        VupShionImportPatchModule,
        ChaofanModImportPatchModule,
        JacketNoAnoKoImportPatchModule,
        OriImportPatchModule,
        SpireBiomesImportPatchModule,
    )
}
