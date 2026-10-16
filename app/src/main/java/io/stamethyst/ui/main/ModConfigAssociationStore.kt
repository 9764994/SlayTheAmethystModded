package io.stamethyst.ui.main

import android.content.Context
import androidx.core.content.edit
import io.stamethyst.model.ModItemUi

internal object ModConfigAssociationStore {
    // Prefer the original manifest ID so aliases, file renames and disabled mod paths do not break the association.
    private fun key(mod: ModItemUi): String = mod.manifestModId.ifBlank { mod.modId }.ifBlank { mod.storagePath }

    fun load(context: Context, mod: ModItemUi): Set<String> = context
        .getSharedPreferences("ModConfigAssociations", Context.MODE_PRIVATE)
        .getStringSet(key(mod), emptySet()).orEmpty().toSet()

    fun associate(context: Context, mod: ModItemUi, path: String) {
        val paths = load(context, mod) + path
        context.getSharedPreferences("ModConfigAssociations", Context.MODE_PRIVATE).edit {
            putStringSet(key(mod), paths)
        }
    }

    fun remove(context: Context, mod: ModItemUi, path: String) {
        val paths = load(context, mod) - path
        context.getSharedPreferences("ModConfigAssociations", Context.MODE_PRIVATE).edit {
            putStringSet(key(mod), paths)
        }
    }
}
