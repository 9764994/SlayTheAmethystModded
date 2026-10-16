package io.stamethyst.backend.mods.importing.patches.mods.ori

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

internal object OriImportPatchModule : ImportPatchModule {
    override val targetModId = "another ori mod"
    override val id = "mod.ori.fast_blur"
    override val version = 1
    override val displayNameResId = R.string.mod_import_patch_ori_title
    override val summaryResId = R.string.mod_import_patch_ori_summary
    override val category = ImportPatchCategory.Shader
    override val defaultEnabled = true
    override val userConfigurable = true
    override val order = 640
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
        val result = OriImportCompatPatcher.patchInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.importString(displayNameResId),
            applied = result.hasAnyPatch,
            summary = if (result.hasAnyPatch) {
                context.importString(R.string.mod_import_patch_ori_applied)
            } else {
                context.importString(R.string.mod_import_patch_ori_noop)
            },
            details = listOf(
                context.importString(R.string.mod_import_patch_ori_detail_shader_files, result.patchedShaderEntries),
                context.importString(R.string.mod_import_patch_ori_detail_gaussian, result.patchedGaussianBlurShaderEntries),
                context.importString(R.string.mod_import_patch_ori_detail_box, result.patchedBoxBlurShaderEntries),
                context.importString(
                    R.string.mod_import_patch_ori_detail_samples,
                    result.estimatedTextureSamplesBefore,
                    result.estimatedTextureSamplesAfter
                )
            ),
            metrics = mapOf(
                "patchedShaderEntries" to result.patchedShaderEntries,
                "patchedGaussianBlurShaderEntries" to result.patchedGaussianBlurShaderEntries,
                "patchedBoxBlurShaderEntries" to result.patchedBoxBlurShaderEntries,
                "estimatedTextureSamplesBefore" to result.estimatedTextureSamplesBefore,
                "estimatedTextureSamplesAfter" to result.estimatedTextureSamplesAfter
            )
        )
    }
}
