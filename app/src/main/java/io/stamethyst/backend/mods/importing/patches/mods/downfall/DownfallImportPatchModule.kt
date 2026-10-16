package io.stamethyst.backend.mods.importing.patches.mods.downfall

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.mods.importing.ImportPatchCategory
import io.stamethyst.backend.mods.importing.ImportPatchFailurePolicy
import io.stamethyst.backend.mods.importing.ImportPatchPlan
import io.stamethyst.backend.mods.importing.ImportPatchResult
import io.stamethyst.backend.mods.importing.ModImportDecisions
import io.stamethyst.backend.mods.importing.ModImportItemPlan
import io.stamethyst.backend.mods.importing.importString
import io.stamethyst.backend.mods.importing.patches.ImportPatchModule
import java.io.File

internal object DownfallImportPatchModule : ImportPatchModule {
    override val targetModId = "downfall"
    override val id = "mod.downfall.mobile_layout"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_downfall_title
    override val summaryResId = R.string.mod_import_patch_downfall_summary
    override val category = ImportPatchCategory.ModSpecific
    override val defaultEnabled = true
    override val userConfigurable = true
    override val order = 620
    override val failurePolicy = ImportPatchFailurePolicy.SkipPatchContinueImport

    override fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan? {
        if (item.normalizedModId != targetModId) return null
        return basePlan(applicable = true)
    }

    override fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult {
        val result = DownfallImportCompatPatcher.patchInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.importString(displayNameResId),
            applied = result.patchedClassEntries > 0,
            summary = if (result.patchedClassEntries > 0) {
                context.importString(R.string.mod_import_patch_downfall_applied)
            } else {
                context.importString(R.string.mod_import_patch_downfall_noop)
            },
            details = listOf(
                context.importString(R.string.mod_import_patch_downfall_detail_classes, result.patchedClassEntries),
                context.importString(R.string.mod_import_patch_downfall_detail_merchant, result.patchedMerchantClassEntries),
                context.importString(R.string.mod_import_patch_downfall_detail_hexaghost, result.patchedHexaghostBodyClassEntries),
                context.importString(R.string.mod_import_patch_downfall_detail_boss_panel, result.patchedBossMechanicPanelClassEntries)
            ),
            metrics = mapOf(
                "patchedClassEntries" to result.patchedClassEntries,
                "patchedMerchantClassEntries" to result.patchedMerchantClassEntries,
                "patchedHexaghostBodyClassEntries" to result.patchedHexaghostBodyClassEntries,
                "patchedBossMechanicPanelClassEntries" to result.patchedBossMechanicPanelClassEntries
            )
        )
    }
}
