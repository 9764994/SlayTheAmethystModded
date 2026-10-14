package io.stamethyst.ui.settings.files

import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** File-granularity selection: unlock files may contain both cards and relics. */
object SaveImportPreview {
    enum class Category { CHARACTER_PROGRESS, UNLOCKS, OTHER_PREFERENCES, HISTORY, ACTIVE_RUN }
    data class Item(
        val path: String,
        val category: Category,
        val change: SaveArchiveResolver.Change,
        val sourceBytes: Long,
        val character: String? = null,
        val fields: List<FieldChange> = emptyList(),
        val contentRecognized: Boolean = false,
        val targetFingerprint: String? = null,
    )
    data class FieldChange(val key: String, val before: String?, val after: String?)
    data class Summary(
        val items: List<Item>,
        val addedHistory: Int,
        val replacedHistory: Int,
        val addedActiveRuns: Int,
        val replacedActiveRuns: Int,
    )

    fun build(candidate: SaveArchiveResolver.Candidate, targetRoot: File, archive: File? = null): Summary {
        val sourceFields = if (archive == null) emptyMap() else ZipFile(archive).use { zip ->
            candidate.entries.filter { it.target.startsWith("preferences/") && it.size <= 1024 * 1024 }.associate { entry ->
                entry.target to runCatching {
                    zip.getInputStream(zip.getEntry(entry.source)).bufferedReader().use { parseFields(it.readText()) }
                }.getOrNull()
            }
        }
        val items = SaveArchiveResolver.changes(candidate, targetRoot).map { change ->
            val path = change.entry.target
            val name = path.substringAfterLast('/').removeSuffix(".backUp").replace(Regex("^[0-9]+_"), "")
            val category = when {
                path.startsWith("runs/") -> Category.HISTORY
                path.startsWith("saves/") -> Category.ACTIVE_RUN
                name.startsWith("STSData") -> Category.CHARACTER_PROGRESS
                name == "STSUnlocks" || name == "STSUnlockProgress" -> Category.UNLOCKS
                else -> Category.OTHER_PREFERENCES
            }
            val after = sourceFields[path]
            val before = runCatching {
                val local = File(targetRoot, path)
                if (local.isFile && local.length() <= 1024 * 1024) parseFields(local.readText()) else null
            }.getOrNull()
            val fields = if (after == null) emptyList() else (after.keys + before.orEmpty().keys).sorted().mapNotNull { key ->
                val old = before?.get(key)
                val new = after[key]
                if (old == new) null else FieldChange(key, old, new)
            }
            val character = if (category == Category.CHARACTER_PROGRESS) when (name.removePrefix("STSData")) {
                "Vagabond" -> "铁甲战士"
                "TheSilent" -> "静默猎手"
                "Defect" -> "故障机器人"
                "Watcher" -> "观者"
                else -> name.removePrefix("STSData")
            } else null
            val local = File(targetRoot, path)
            Item(path, category, change.change, change.entry.size, character, fields, after != null,
                if (local.isFile) SaveArchiveResolver.hash(local) else null)
        }
        // Backups and unrelated auxiliary files are not separate playable runs.
        fun count(category: Category, change: SaveArchiveResolver.Change, suffix: String): Int =
            items.count { it.category == category && it.change == change && it.path.endsWith(suffix) }
        return Summary(
            items,
            count(Category.HISTORY, SaveArchiveResolver.Change.ADDED, ".run"),
            count(Category.HISTORY, SaveArchiveResolver.Change.REPLACED, ".run"),
            count(Category.ACTIVE_RUN, SaveArchiveResolver.Change.ADDED, ".autosave"),
            count(Category.ACTIVE_RUN, SaveArchiveResolver.Change.REPLACED, ".autosave"),
        )
    }

    // Preserve actual field names and values: unknown/mod-specific progress is never guessed.
    private fun parseFields(text: String): Map<String, String>? {
        val obj = Json.parseToJsonElement(text) as? JsonObject ?: return null
        return obj.mapValues { (_, value) ->
            if (value is JsonPrimitive) value.content else value.toString()
        }
    }
}
