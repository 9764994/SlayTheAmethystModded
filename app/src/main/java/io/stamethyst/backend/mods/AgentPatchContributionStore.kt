package io.stamethyst.backend.mods

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local receipts are written only after the upload endpoint acknowledges success. */
object AgentPatchContributionStore {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    private fun key(parentId: String, patchModId: String) = "${parentId.length}:$parentId:$patchModId"

    fun uploadedVersion(context: Context, parentId: String, patchModId: String): String? =
        context.getSharedPreferences("ai_patch_contributions", Context.MODE_PRIVATE)
            .getString(key(parentId, patchModId), null)

    fun recordUpload(context: Context, parentId: String, patchModId: String, version: String) {
        context.getSharedPreferences("ai_patch_contributions", Context.MODE_PRIVATE).edit()
            .putString(key(parentId, patchModId), version).apply()
        changes.value += 1
    }
}
