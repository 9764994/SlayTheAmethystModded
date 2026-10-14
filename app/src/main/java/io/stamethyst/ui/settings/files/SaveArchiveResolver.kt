package io.stamethyst.ui.settings.files

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Wrapper-independent resolution. Plans are shared by preview, selection and extraction. */
object SaveArchiveResolver {
    data class Entry(val source: String, val target: String, val size: Long, val sha256: String)
    data class Candidate(val prefix: String, val entries: List<Entry>)
    enum class Change { ADDED, REPLACED, UNCHANGED }
    data class FileChange(val entry: Entry, val change: Change)

    fun inspect(archive: File): List<Candidate> {
        val groups = linkedMapOf<String, MutableList<Entry>>()
        var total = 0L
        var fileCount = 0
        ZipFile(archive).use { zip ->
            val names = mutableSetOf<String>()
            for (entry in zip.entries().asSequence()) {
                if (!names.add(entry.name)) throw IOException("Duplicate ZIP entry: ${entry.name}")
                val segments = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                if (segments.any { it == ".." || it == "." } || entry.name.startsWith('/')) {
                    throw IOException("Unsafe ZIP entry: ${entry.name}")
                }
                if (entry.isDirectory || segments.any { it == "__MACOSX" } || segments.lastOrNull()?.startsWith(".") == true) continue
                val index = segments.indexOfFirst { canonicalRoot(it) != null }
                val prefix: String
                val target: String
                if (index >= 0 && index < segments.lastIndex) {
                    prefix = segments.take(index).joinToString("/")
                    val root = canonicalRoot(segments[index])!!
                    var start = index + 1
                    while (start < segments.lastIndex && canonicalRoot(segments[start]) == root) start++
                    target = "$root/${segments.drop(start).joinToString("/")}"
                } else {
                    val name = segments.lastOrNull() ?: continue
                    val root = when {
                        isPreference(name) -> "preferences"
                        name.endsWith(".autosave") || name.endsWith(".autosave.bak") -> "saves"
                        name.endsWith(".run") -> "runs"
                        else -> continue
                    }
                    prefix = segments.dropLast(1).joinToString("/")
                    target = "$root/$name"
                }
                if (++fileCount > 20000) throw IOException("Too many save files")
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val crc = java.util.zip.CRC32()
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        total += count
                        if (size > 64L * 1024 * 1024 || total > 512L * 1024 * 1024) throw IOException("Save archive is too large")
                        digest.update(buffer, 0, count)
                        crc.update(buffer, 0, count)
                    }
                }
                if (entry.crc != crc.value) throw IOException("Corrupt ZIP entry: ${entry.name}")
                val resolved = Entry(entry.name, target, size, hex(digest.digest()))
                val group = groups.getOrPut(prefix) { mutableListOf() }
                val previous = group.firstOrNull { it.target == target }
                if (previous != null && previous.sha256 != resolved.sha256) throw IOException("Conflicting save files: $target")
                if (previous == null) group.add(resolved)
            }
        }
        return groups.map { Candidate(it.key, it.value.sortedBy { entry -> entry.target }) }
    }

    fun changes(candidate: Candidate, targetRoot: File): List<FileChange> = candidate.entries.map { entry ->
        val target = File(targetRoot, entry.target)
        requireInside(targetRoot, target)
        val change = when {
            !target.exists() -> Change.ADDED
            !target.isFile -> throw IOException("Save target is not a file: ${entry.target}")
            hash(target) == entry.sha256 -> Change.UNCHANGED
            else -> Change.REPLACED
        }
        FileChange(entry, change)
    }

    fun extract(archive: File, candidate: Candidate, staging: File, selectedPaths: Set<String>) {
        if (!candidate.entries.map { it.target }.containsAll(selectedPaths)) throw IOException("Unknown save selection")
        ZipFile(archive).use { zip ->
            candidate.entries.filter { it.target in selectedPaths }.forEach { entry ->
                val output = File(staging, entry.target)
                requireInside(staging, output)
                val parent = output.parentFile ?: throw IOException("Save directory unavailable")
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create save directory")
                zip.getInputStream(zip.getEntry(entry.source) ?: throw IOException("Save entry disappeared")).use { input ->
                    output.outputStream().use { input.copyTo(it) }
                }
                if (output.length() != entry.size || hash(output) != entry.sha256) throw IOException("Save archive changed: ${entry.source}")
            }
        }
    }

    private fun canonicalRoot(name: String): String? = when (name.lowercase(java.util.Locale.ROOT)) {
        "preferences", "perference", "perferences" -> "preferences"
        "runs" -> "runs"
        "saves" -> "saves"
        else -> null
    }

    private fun isPreference(name: String): Boolean {
        val base = name.removeSuffix(".backUp").replace(Regex("^[0-9]+_"), "")
        return base.startsWith("STSData") || base in setOf("STSPlayer", "STSSaveSlots", "STSUnlocks", "STSUnlockProgress", "STSAchievements", "STSSeenCards", "STSSeenRelics", "STSSeenBosses", "STSSeenEnemies", "STSGameplaySettings", "STSSound", "STSGraphics")
    }

    private fun requireInside(root: File, file: File) {
        if (!file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) || file.canonicalFile == root.canonicalFile) throw IOException("Unsafe save target")
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    internal fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }
}
