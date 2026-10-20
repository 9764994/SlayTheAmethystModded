package io.stamethyst.ui.main

import android.content.Context
import android.content.SharedPreferences
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.model.ModItemUi
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import org.json.JSONArray
import org.json.JSONObject

internal class MainFolderStateStore {
    private val modFolders = ArrayList<MainScreenViewModel.ModFolder>()
    private val folderAssignments = LinkedHashMap<String, String>()
    private val folderCollapsed = LinkedHashMap<String, Boolean>()
    private var unassignedCollapsed = false
    private var dependencyFolderCollapsed = true
    private var dragLocked = false
    private var unassignedFolderName = DEFAULT_UNASSIGNED_FOLDER_NAME
    private var unassignedFolderOrder = 0
    private var loaded = false

    val folders: MutableList<MainScreenViewModel.ModFolder>
        get() = modFolders
    val assignments: MutableMap<String, String>
        get() = folderAssignments
    val collapsedMap: MutableMap<String, Boolean>
        get() = folderCollapsed
    var unassignedIsCollapsed: Boolean
        get() = unassignedCollapsed
        set(value) {
            unassignedCollapsed = value
        }
    var dependencyFolderIsCollapsed: Boolean
        get() = dependencyFolderCollapsed
        set(value) {
            dependencyFolderCollapsed = value
        }
    var isDragLocked: Boolean
        get() = dragLocked
        set(value) {
            dragLocked = value
        }
    var unassignedName: String
        get() = unassignedFolderName
        set(value) {
            unassignedFolderName = value
        }
    var unassignedOrder: Int
        get() = unassignedFolderOrder
        set(value) {
            unassignedFolderOrder = value.coerceIn(0, modFolders.size)
        }

    fun ensureLoaded(host: Context) {
        if (loaded) {
            return
        }
        load(host)
        loaded = true
    }

    fun reload(host: Context) {
        load(host)
        loaded = true
    }

    fun persist(host: Context, synchronous: Boolean = false) {
        val foldersArray = JSONArray()
        modFolders.forEach { folder ->
            val item = JSONObject()
            item.put("id", folder.id)
            item.put("name", folder.name)
            foldersArray.put(item)
        }

        val assignmentsObject = JSONObject()
        folderAssignments.forEach { (modKey, folderId) ->
            assignmentsObject.put(modKey, folderId)
        }

        val collapsedObject = JSONObject()
        folderCollapsed.forEach { (folderId, collapsed) ->
            collapsedObject.put(folderId, collapsed)
        }

        val preferences = prefs(host)
        val expected = mapOf(
            KEY_FOLDERS to foldersArray.toString(),
            KEY_ASSIGNMENTS to assignmentsObject.toString(),
            KEY_COLLAPSED to collapsedObject.toString(),
            KEY_UNASSIGNED_COLLAPSED to unassignedCollapsed,
            KEY_DEPENDENCY_FOLDER_COLLAPSED to dependencyFolderCollapsed,
            KEY_DRAG_LOCKED to dragLocked,
            KEY_UNASSIGNED_NAME to unassignedFolderName,
            KEY_UNASSIGNED_ORDER to unassignedFolderOrder.coerceIn(0, modFolders.size),
        )
        val stored = preferences.all
        if (expected.all { (key, value) -> stored[key] == value }) return
        val editor = preferences.edit()
            .putString(KEY_FOLDERS, foldersArray.toString())
            .putString(KEY_ASSIGNMENTS, assignmentsObject.toString())
            .putString(KEY_COLLAPSED, collapsedObject.toString())
            .putBoolean(KEY_UNASSIGNED_COLLAPSED, unassignedCollapsed)
            .putBoolean(KEY_DEPENDENCY_FOLDER_COLLAPSED, dependencyFolderCollapsed)
            .putBoolean(KEY_DRAG_LOCKED, dragLocked)
            .putString(KEY_UNASSIGNED_NAME, unassignedFolderName)
            .putInt(KEY_UNASSIGNED_ORDER, unassignedFolderOrder.coerceIn(0, modFolders.size))
        if (synchronous) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    fun sanitize(optionalMods: List<ModItemUi>) {
        val validFolderIds = modFolders.map { it.id }.toHashSet()
        val normalized = LinkedHashMap<String, String>()
        optionalMods.forEach { mod ->
            val primaryKey = resolveModAssignmentKey(mod) ?: return@forEach
            val assignedFolderId = resolveAssignedFolderId(mod, folderAssignments, validFolderIds)
            if (!assignedFolderId.isNullOrBlank() && validFolderIds.contains(assignedFolderId)) {
                normalized[primaryKey] = assignedFolderId
            }
        }
        folderAssignments.clear()
        folderAssignments.putAll(normalized)

        val normalizedCollapsed = LinkedHashMap<String, Boolean>()
        modFolders.forEach { folder ->
            normalizedCollapsed[folder.id] = folderCollapsed[folder.id] == true
        }
        folderCollapsed.clear()
        folderCollapsed.putAll(normalizedCollapsed)
        unassignedFolderOrder = unassignedFolderOrder.coerceIn(0, modFolders.size)
    }

    fun buildFolderOrderTokens(): List<String> {
        val tokens = modFolders.map { it.id }.toMutableList()
        val insertIndex = unassignedFolderOrder.coerceIn(0, tokens.size)
        tokens.add(insertIndex, UNASSIGNED_FOLDER_ID)
        return tokens
    }

    fun applyFolderOrderTokens(tokens: List<String>) {
        val folderMap = modFolders.associateBy { it.id }
        val reordered = tokens
            .filter { it != UNASSIGNED_FOLDER_ID }
            .mapNotNull { folderMap[it] }
        modFolders.clear()
        modFolders.addAll(reordered)
        unassignedFolderOrder = tokens.indexOf(UNASSIGNED_FOLDER_ID)
            .coerceAtLeast(0)
            .coerceIn(0, modFolders.size)
    }

    fun moveFolderToken(host: Context, folderId: String, offset: Int): Boolean {
        ensureLoaded(host)
        val tokens = buildFolderOrderTokens().toMutableList()
        val fromIndex = tokens.indexOf(folderId)
        if (fromIndex < 0) {
            return false
        }
        val toIndex = fromIndex + offset
        if (toIndex !in tokens.indices) {
            return false
        }
        tokens.add(toIndex, tokens.removeAt(fromIndex))
        applyFolderOrderTokens(tokens)
        persist(host)
        return true
    }

    @Suppress("DEPRECATION")
    private fun prefs(host: Context): SharedPreferences {
        return host.getSharedPreferences(
            PREFS_MAIN_MOD_FOLDERS,
            Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
        )
    }

    private fun load(host: Context) {
        val preferences = prefs(host)
        modFolders.clear()
        folderAssignments.clear()
        folderCollapsed.clear()

        runCatching {
            val array = JSONArray(preferences.getString(KEY_FOLDERS, "[]") ?: "[]")
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                if (id.isEmpty() || name.isEmpty()) {
                    continue
                }
                modFolders.add(MainScreenViewModel.ModFolder(id = id, name = name))
            }
        }

        runCatching {
            val obj = JSONObject(preferences.getString(KEY_ASSIGNMENTS, "{}") ?: "{}")
            val keys = obj.keys()
            while (keys.hasNext()) {
                val modKey = keys.next().trim()
                val folderId = obj.optString(modKey).trim()
                if (modKey.isNotEmpty() && folderId.isNotEmpty()) {
                    folderAssignments[modKey] = folderId
                }
            }
        }

        runCatching {
            val obj = JSONObject(preferences.getString(KEY_COLLAPSED, "{}") ?: "{}")
            val keys = obj.keys()
            while (keys.hasNext()) {
                val folderId = keys.next().trim()
                if (folderId.isNotEmpty()) {
                    folderCollapsed[folderId] = obj.optBoolean(folderId, false)
                }
            }
        }

        unassignedCollapsed = preferences.getBoolean(KEY_UNASSIGNED_COLLAPSED, false)
        dependencyFolderCollapsed = if (preferences.contains(KEY_DEPENDENCY_FOLDER_COLLAPSED)) {
            preferences.getBoolean(KEY_DEPENDENCY_FOLDER_COLLAPSED, true)
        } else {
            preferences.getBoolean(KEY_LEGACY_STATUS_SUMMARY_COLLAPSED, true)
        }
        dragLocked = preferences.getBoolean(KEY_DRAG_LOCKED, false)
        unassignedFolderName = preferences.getString(KEY_UNASSIGNED_NAME, DEFAULT_UNASSIGNED_FOLDER_NAME)
            ?.trim()
            ?.ifEmpty { DEFAULT_UNASSIGNED_FOLDER_NAME }
            ?: DEFAULT_UNASSIGNED_FOLDER_NAME
        unassignedFolderOrder = preferences.getInt(KEY_UNASSIGNED_ORDER, 0).coerceIn(0, modFolders.size)
    }

    private fun resolveModAssignmentKey(mod: ModItemUi): String? {
        val storage = mod.storagePath.trim()
        if (storage.isNotEmpty()) {
            return storage
        }
        return resolveStoredOptionalModId(mod)
    }

    companion object {
        private const val PREFS_MAIN_MOD_FOLDERS = "MainModFolders"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_ASSIGNMENTS = "assignments"
        private const val KEY_COLLAPSED = "collapsed"
        private const val KEY_UNASSIGNED_COLLAPSED = "unassigned_collapsed"
        private const val KEY_DEPENDENCY_FOLDER_COLLAPSED = "dependency_folder_collapsed"
        private const val KEY_DRAG_LOCKED = "drag_locked"
        private const val KEY_LEGACY_STATUS_SUMMARY_COLLAPSED = "status_summary_collapsed"
        private const val KEY_UNASSIGNED_NAME = "unassigned_name"
        private const val KEY_UNASSIGNED_ORDER = "unassigned_order"
        private const val DEFAULT_UNASSIGNED_FOLDER_NAME = "未分类"
    }
}

internal object MainFolderAssignmentHandoffStore {
    private data class PendingAssignment(
        val targetStoragePath: String,
        val folderId: String,
        val sourceStoragePaths: List<String>,
        val normalizedModId: String
    )

    private const val FILE_NAME = "pending_mod_folder_assignments.json"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_TARGET_STORAGE_PATH = "targetStoragePath"
    private const val KEY_FOLDER_ID = "folderId"
    private const val KEY_SOURCE_STORAGE_PATHS = "sourceStoragePaths"
    private const val KEY_NORMALIZED_MOD_ID = "normalizedModId"
    private val lock = Any()

    fun enqueueDuplicateFolderReuse(
        context: Context,
        targetStoragePath: String,
        folderId: String,
        sourceStoragePaths: Collection<String>,
        normalizedModId: String
    ) {
        val targetPath = targetStoragePath.trim()
        val targetFolderId = folderId.trim()
        if (targetPath.isEmpty()) {
            return
        }
        synchronized(lock) {
            val entries = readEntries(context)
                .filterNot { it.targetStoragePath == targetPath }
                .toMutableList()
            entries.add(
                PendingAssignment(
                    targetStoragePath = targetPath,
                    folderId = targetFolderId,
                    sourceStoragePaths = sourceStoragePaths
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct(),
                    normalizedModId = ModManager.normalizeModId(normalizedModId)
                )
            )
            writeEntries(context, entries)
        }
    }

    fun consumePendingAssignments(
        context: Context,
        folderStateStore: MainFolderStateStore
    ): Boolean {
        synchronized(lock) {
            val entries = readEntries(context)
            if (entries.isEmpty()) {
                return false
            }
            pendingFile(context).delete()

            val validFolderIds = folderStateStore.folders.map { it.id }.toHashSet()
            var changed = false
            entries.forEach { entry ->
                val targetFolderId = resolvePendingAssignmentFolderId(
                    entry = entry,
                    folderAssignments = folderStateStore.assignments,
                    validFolderIds = validFolderIds
                )
                if (entry.targetStoragePath.isBlank() || targetFolderId.isNullOrBlank()) {
                    return@forEach
                }
                entry.sourceStoragePaths.forEach { sourcePath ->
                    resolveModStoragePathCandidates(sourcePath).forEach { candidate ->
                        if (folderStateStore.assignments.remove(candidate) != null) {
                            changed = true
                        }
                    }
                }
                val normalizedKey = ModManager.normalizeModId(entry.normalizedModId)
                if (normalizedKey.isNotEmpty() && folderStateStore.assignments.remove(normalizedKey) != null) {
                    changed = true
                }
                if (folderStateStore.assignments[entry.targetStoragePath] != targetFolderId) {
                    folderStateStore.assignments[entry.targetStoragePath] = targetFolderId
                    changed = true
                }
            }
            return changed
        }
    }

    private fun resolvePendingAssignmentFolderId(
        entry: PendingAssignment,
        folderAssignments: Map<String, String>,
        validFolderIds: Set<String>
    ): String? {
        val directFolderId = entry.folderId.trim()
        if (directFolderId.isNotEmpty() && validFolderIds.contains(directFolderId)) {
            return directFolderId
        }
        entry.sourceStoragePaths.forEach { sourcePath ->
            resolveModStoragePathCandidates(sourcePath).forEach { candidate ->
                val folderId = folderAssignments[candidate]
                if (!folderId.isNullOrBlank() && validFolderIds.contains(folderId)) {
                    return folderId
                }
            }
        }
        val normalizedKey = ModManager.normalizeModId(entry.normalizedModId)
        if (normalizedKey.isNotEmpty()) {
            val folderId = folderAssignments[normalizedKey]
            if (!folderId.isNullOrBlank() && validFolderIds.contains(folderId)) {
                return folderId
            }
        }
        return null
    }

    private fun readEntries(context: Context): List<PendingAssignment> {
        val file = pendingFile(context)
        if (!file.isFile) {
            return emptyList()
        }
        return try {
            val root = JSONObject(file.readText(StandardCharsets.UTF_8))
            val array = root.optJSONArray(KEY_ENTRIES) ?: return emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val targetPath = item.optString(KEY_TARGET_STORAGE_PATH).trim()
                    val folderId = item.optString(KEY_FOLDER_ID).trim()
                    if (targetPath.isEmpty()) {
                        continue
                    }
                    val sourcePaths = ArrayList<String>()
                    val sourceArray = item.optJSONArray(KEY_SOURCE_STORAGE_PATHS)
                    if (sourceArray != null) {
                        for (sourceIndex in 0 until sourceArray.length()) {
                            val sourcePath = sourceArray.optString(sourceIndex).trim()
                            if (sourcePath.isNotEmpty()) {
                                sourcePaths.add(sourcePath)
                            }
                        }
                    }
                    add(
                        PendingAssignment(
                            targetStoragePath = targetPath,
                            folderId = folderId,
                            sourceStoragePaths = sourcePaths.distinct(),
                            normalizedModId = item.optString(KEY_NORMALIZED_MOD_ID).trim()
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeEntries(context: Context, entries: List<PendingAssignment>) {
        val file = pendingFile(context)
        if (entries.isEmpty()) {
            file.delete()
            return
        }
        val parent = file.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) {
            return
        }
        val root = JSONObject().put(
            KEY_ENTRIES,
            JSONArray().apply {
                entries.forEach { entry ->
                    put(
                        JSONObject()
                            .put(KEY_TARGET_STORAGE_PATH, entry.targetStoragePath)
                            .put(KEY_FOLDER_ID, entry.folderId)
                            .put(
                                KEY_SOURCE_STORAGE_PATHS,
                                JSONArray().apply { entry.sourceStoragePaths.forEach(::put) }
                            )
                            .put(KEY_NORMALIZED_MOD_ID, entry.normalizedModId)
                    )
                }
            }
        )
        val tempFile = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        runCatching {
            tempFile.writeText(root.toString(), StandardCharsets.UTF_8)
            if (file.exists() && !file.delete()) {
                tempFile.delete()
                return
            }
            if (!tempFile.renameTo(file)) {
                tempFile.delete()
            }
        }.onFailure {
            tempFile.delete()
        }
    }

    private fun pendingFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }
}
