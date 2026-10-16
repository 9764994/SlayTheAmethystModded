package io.stamethyst.backend.mods.importing.patches

import android.content.Context
import androidx.annotation.StringRes
import io.stamethyst.backend.mods.importing.ImportPatchCategory
import io.stamethyst.backend.mods.importing.ImportPatchFailurePolicy
import io.stamethyst.backend.mods.importing.ImportPatchPlan
import io.stamethyst.backend.mods.importing.ImportPatchResult
import io.stamethyst.backend.mods.importing.ModImportItemPlan
import io.stamethyst.backend.mods.importing.ModImportPlan
import io.stamethyst.backend.mods.importing.ModImportDecisions
import java.io.File

internal interface ImportPatchModule {
    val id: String
    val version: Int
    @get:StringRes
    val displayNameResId: Int
    @get:StringRes
    val summaryResId: Int
    val displayName: String
        get() = id
    val summary: String
        get() = id
    val category: ImportPatchCategory
    val defaultEnabled: Boolean
    val userConfigurable: Boolean
    /** Mod-ID-only applicability, safe to query while loading installed cards without scanning jars. */
    val targetModId: String?
        get() = null
    /**
     * Some expensive candidate scans can be deferred by callers such as the workshop importer.
     * This is intentionally separate from [userConfigurable], because every registered patch can
     * now be controlled by the import-patch manager.
     */
    val supportsDeferredInspection: Boolean
        get() = false
    val order: Int
    val failurePolicy: ImportPatchFailurePolicy

    fun isAvailable(context: Context): Boolean = true

    fun plan(context: Context, item: ModImportItemPlan): ImportPatchPlan?

    fun plan(context: Context, item: ModImportItemPlan, inspectionJar: File): ImportPatchPlan? {
        return plan(context, item)
    }

    fun apply(
        context: Context,
        workingJar: File,
        item: ModImportItemPlan,
        plan: ImportPatchPlan,
        decisions: ModImportDecisions
    ): ImportPatchResult

    fun basePlan(applicable: Boolean, details: List<String> = emptyList()): ImportPatchPlan {
        return ImportPatchPlan(
            moduleId = id,
            moduleVersion = version,
            displayNameResId = displayNameResId,
            summaryResId = summaryResId,
            displayName = displayName,
            summary = summary,
            category = category,
            defaultEnabled = this.defaultEnabled,
            userConfigurable = this.userConfigurable,
            failurePolicy = failurePolicy,
            applicable = applicable,
            details = details
        )
    }
}

internal fun ModImportPlan.patchModuleIdsForItem(itemId: String): Set<String> {
    return items.firstOrNull { it.id == itemId }
        ?.patchPlans
        ?.mapTo(LinkedHashSet()) { it.moduleId }
        ?: emptySet()
}
