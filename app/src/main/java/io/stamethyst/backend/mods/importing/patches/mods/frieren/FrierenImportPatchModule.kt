package io.stamethyst.backend.mods.importing.patches.mods.frieren

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

internal object FrierenImportPatchModule : ImportPatchModule {
    override val targetModId = "frierenmod"
    override val id = "mod.frieren.anti_pirate"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_frieren_title
    override val summaryResId = R.string.mod_import_patch_frieren_summary
    override val category = ImportPatchCategory.ModSpecific
    override val defaultEnabled = true
    override val userConfigurable = true
    override val order = 610
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
        val result = FrierenModCompatPatcher.patchAntiPirateInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.importString(displayNameResId),
            applied = result.patchedAntiPirateMethod,
            summary = if (result.patchedAntiPirateMethod) {
                context.importString(R.string.mod_import_patch_frieren_applied)
            } else {
                context.importString(R.string.mod_import_patch_frieren_noop)
            },
            details = listOf(context.importString(R.string.mod_import_patch_frieren_detail, result.patchedAntiPirateMethod.toString()))
        )
    }
}
