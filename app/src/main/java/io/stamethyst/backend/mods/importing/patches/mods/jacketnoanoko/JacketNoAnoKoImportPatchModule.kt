package io.stamethyst.backend.mods.importing.patches.mods.jacketnoanoko

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

internal object JacketNoAnoKoImportPatchModule : ImportPatchModule {
    override val targetModId = "jacketnoanokomod"
    override val id = "mod.jacketnoanoko.shader"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_jacketnoanoko_title
    override val summaryResId = R.string.mod_import_patch_jacketnoanoko_summary
    override val category = ImportPatchCategory.Shader
    override val defaultEnabled = true
    override val userConfigurable = true
    override val order = 520
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
        val result = JacketNoAnoKoModCompatPatcher.patchInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.importString(displayNameResId),
            applied = result.hasAnyPatch,
            summary = if (result.hasAnyPatch) {
                context.importString(R.string.mod_import_patch_jacketnoanoko_applied)
            } else {
                context.importString(R.string.mod_import_patch_jacketnoanoko_noop)
            },
            details = listOf(
                context.importString(R.string.mod_import_patch_jacketnoanoko_detail_shader_files, result.patchedShaderEntries),
                context.importString(R.string.mod_import_patch_jacketnoanoko_detail_version_directives, result.removedDesktopVersionDirectives),
                context.importString(R.string.mod_import_patch_jacketnoanoko_detail_precision_blocks, result.insertedFragmentPrecisionBlocks)
            ),
            metrics = mapOf(
                "patchedShaderEntries" to result.patchedShaderEntries,
                "removedDesktopVersionDirectives" to result.removedDesktopVersionDirectives,
                "insertedFragmentPrecisionBlocks" to result.insertedFragmentPrecisionBlocks
            )
        )
    }
}
