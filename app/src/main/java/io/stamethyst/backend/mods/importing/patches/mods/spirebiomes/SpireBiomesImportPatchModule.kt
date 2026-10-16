package io.stamethyst.backend.mods.importing.patches.mods.spirebiomes

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

internal object SpireBiomesImportPatchModule : ImportPatchModule {
    override val targetModId = "anniv6"
    override val id = "mod.spirebiomes.fog_fbm"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_spirebiomes_title
    override val summaryResId = R.string.mod_import_patch_spirebiomes_summary
    override val category = ImportPatchCategory.Shader
    override val defaultEnabled = true
    override val userConfigurable = true
    override val order = 650
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
        val result = SpireBiomesImportCompatPatcher.patchInPlace(workingJar)
        val summaryRes = when {
            result.hasAnyPatch -> R.string.mod_import_patch_spirebiomes_applied
            result.unsupportedShaderEntries > 0 -> R.string.mod_import_patch_spirebiomes_unsupported
            else -> R.string.mod_import_patch_spirebiomes_noop
        }
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.importString(displayNameResId),
            applied = result.hasAnyPatch,
            summary = context.importString(summaryRes),
            details = listOf(
                context.importString(
                    R.string.mod_import_patch_spirebiomes_detail,
                    result.patchedShaderEntries,
                    result.alreadyPatchedShaderEntries,
                    result.unsupportedShaderEntries
                )
            ),
            metrics = mapOf(
                "patchedShaderEntries" to result.patchedShaderEntries,
                "alreadyPatchedShaderEntries" to result.alreadyPatchedShaderEntries,
                "unsupportedShaderEntries" to result.unsupportedShaderEntries
            )
        )
    }
}
