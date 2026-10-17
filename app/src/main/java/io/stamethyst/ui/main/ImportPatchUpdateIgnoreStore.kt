package io.stamethyst.ui.main

import android.content.Context
import io.stamethyst.model.ModItemUi
import java.util.LinkedHashSet

/**
 * Remembers which launcher import-patch updates the user chose not to be warned about.
 *
 * A patch update is identified by the installed mod jar, the patch module and the patch
 * version. Bumping the module version or adding another outdated patch produces a new key,
 * so the launch warning appears again for anything the user has not seen yet.
 */
internal object ImportPatchUpdateIgnoreStore {
    fun hasUnignoredUpdates(context: Context, mods: List<ModItemUi>): Boolean {
        return hasUnignoredUpdates(
            currentKeys = collectUpdateKeys(mods),
            ignoredKeys = loadIgnoredKeys(context),
        )
    }

    fun rememberIgnoredUpdates(context: Context, mods: List<ModItemUi>): Boolean {
        val currentKeys = collectUpdateKeys(mods)
        if (currentKeys.isEmpty()) {
            return false
        }
        val updated = LinkedHashSet(loadIgnoredKeys(context))
        val changed = updated.addAll(currentKeys)
        if (changed) {
            prefs(context).edit()
                .putStringSet(KEY_IGNORED_PATCH_UPDATES, updated)
                .apply()
        }
        return changed
    }

    internal fun collectUpdateKeys(mods: List<ModItemUi>): Set<String> {
        val keys = LinkedHashSet<String>()
        mods.forEach { mod ->
            if (!mod.needsImportPatchUpdate) {
                return@forEach
            }
            val storagePath = mod.storagePath.trim()
            if (storagePath.isEmpty()) {
                return@forEach
            }
            val patches = mod.updatableImportPatches
            if (patches.isEmpty()) {
                // Defensive fallback for a jar whose outdated state is recorded but whose
                // per-module records are no longer populated.
                keys += buildUpdateKey(
                    storagePath = storagePath,
                    moduleId = UNKNOWN_PATCH_MODULE_ID,
                    patchVersion = 0,
                )
                return@forEach
            }
            patches.forEach { patch ->
                val moduleId = patch.moduleId.trim()
                if (moduleId.isNotEmpty()) {
                    keys += buildUpdateKey(
                        storagePath = storagePath,
                        moduleId = moduleId,
                        patchVersion = patch.currentVersion,
                    )
                }
            }
        }
        return keys
    }

    internal fun hasUnignoredUpdates(
        currentKeys: Set<String>,
        ignoredKeys: Set<String>,
    ): Boolean {
        return currentKeys.any { key -> key !in ignoredKeys }
    }

    internal fun buildUpdateKey(
        storagePath: String,
        moduleId: String,
        patchVersion: Int,
    ): String {
        return "$storagePath$KEY_SEPARATOR$moduleId$KEY_SEPARATOR$patchVersion"
    }

    private fun loadIgnoredKeys(context: Context): Set<String> {
        return prefs(context)
            .getStringSet(KEY_IGNORED_PATCH_UPDATES, emptySet())
            ?.toSet()
            .orEmpty()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_IMPORT_PATCH_UPDATE_IGNORE, Context.MODE_PRIVATE)

    private const val PREFS_IMPORT_PATCH_UPDATE_IGNORE = "MainImportPatchUpdateIgnoreState"
    private const val KEY_IGNORED_PATCH_UPDATES = "ignored_patch_updates"
    private const val KEY_SEPARATOR = "\u0000"
    private const val UNKNOWN_PATCH_MODULE_ID = "unknown"
}
