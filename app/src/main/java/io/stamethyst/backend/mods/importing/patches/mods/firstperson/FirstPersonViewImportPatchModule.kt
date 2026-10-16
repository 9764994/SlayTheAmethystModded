package io.stamethyst.backend.mods.importing.patches.mods.firstperson

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

internal object FirstPersonViewImportPatchModule : ImportPatchModule {
    override val targetModId = "firstperson"

    override val id = "mod.firstperson.gyro_camera"
    override val version = 2
    override val displayNameResId = R.string.mod_import_patch_firstperson_title
    override val summaryResId = R.string.mod_import_patch_firstperson_summary
    override val category = ImportPatchCategory.ModSpecific
    override val defaultEnabled = true
    override val userConfigurable = true
    override val order = 625
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
        val result = FirstPersonViewImportCompatPatcher.patchInPlace(workingJar)
        return ImportPatchResult(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = context.importString(displayNameResId),
            applied = result.hasAnyPatch,
            summary = if (result.hasAnyPatch) {
                context.importString(R.string.mod_import_patch_firstperson_applied)
            } else {
                context.importString(R.string.mod_import_patch_firstperson_noop)
            },
            details = listOf(
                context.importString(
                    R.string.mod_import_patch_firstperson_detail_classes,
                    result.patchedClassEntries
                ),
                context.importString(
                    R.string.mod_import_patch_firstperson_detail_yaw,
                    result.patchedYawInputCalls
                ),
                context.importString(
                    R.string.mod_import_patch_firstperson_detail_pitch,
                    result.patchedPitchInputCalls
                )
            ),
            metrics = mapOf(
                "patchedClassEntries" to result.patchedClassEntries,
                "patchedYawInputCalls" to result.patchedYawInputCalls,
                "patchedPitchInputCalls" to result.patchedPitchInputCalls
            )
        )
    }
}
