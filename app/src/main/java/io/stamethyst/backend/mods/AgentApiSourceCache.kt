package io.stamethyst.backend.mods

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * On-disk cache for `decompile_agent_api_class` output.
 *
 * Game/API source is never written into the parent mod's `source/` tree, so without this cache every
 * call paid the full CFR cost again. Entries are keyed by the archive fingerprint and the binary
 * class name; the fingerprint is `name-length-mtime` rather than a content hash because hashing the
 * 365 MB game JAR costs more than the decompilation it would protect.
 *
 * The cache lives under the app cache (falling back to internal storage), is bounded, and is
 * pruned oldest-first. A cache miss only costs a slower hit later.
 */
object AgentApiSourceCache {
    private const val CACHE_DIR = "agent-api-source"
    private const val MAX_CACHE_BYTES = 24L * 1024L * 1024L
    private const val MAX_CACHE_FILES = 512

    /** Resolves the writable cache root, or null when the device has no usable scratch directory. */
    fun root(context: Context): File? =
        AgentPatchSourceCompiler.resolveAgentPatchCacheDir(context, CACHE_DIR)

    fun fingerprint(jar: File): String =
        "${jar.name}-${jar.length()}-${jar.lastModified()}"

    fun entryFile(root: File, jar: File, binaryName: String): File? {
        val relative = relativePath(binaryName) ?: return null
        val directory = File(root, sanitize(fingerprint(jar)))
        val file = File(directory, relative)
        val canonicalRoot = runCatching { directory.canonicalFile.toPath() }.getOrNull() ?: return null
        val canonicalFile = runCatching { file.canonicalFile.toPath() }.getOrNull() ?: return null
        return if (canonicalFile.startsWith(canonicalRoot)) file else null
    }

    fun read(root: File, jar: File, binaryName: String): String? {
        val file = entryFile(root, jar, binaryName) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun write(root: File, jar: File, binaryName: String, source: String) {
        val file = entryFile(root, jar, binaryName) ?: return
        val directory = file.parentFile ?: return
        if (!directory.isDirectory && !directory.mkdirs()) return
        val temporary = File(directory, "${file.name}.${System.nanoTime()}.${Thread.currentThread().id}.tmp")
        val written = runCatching {
            temporary.writeText(source, StandardCharsets.UTF_8)
            if (file.exists() && !file.delete()) return@runCatching false
            temporary.renameTo(file)
        }.getOrDefault(false)
        if (!written) temporary.delete()
        prune(root)
    }

    /** Drops the oldest entries once the cache exceeds its byte or file budget. */
    internal fun prune(
        root: File,
        maxBytes: Long = MAX_CACHE_BYTES,
        maxFiles: Int = MAX_CACHE_FILES,
    ) {
        val files = runCatching {
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("java", ignoreCase = true) }
                .toList()
        }.getOrNull() ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        if (total <= maxBytes && count <= maxFiles) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes && count <= maxFiles) return@forEach
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                total -= length
                count--
            }
        }
    }

    private fun relativePath(binaryName: String): String? {
        val normalized = binaryName.trim()
            .removePrefix("L")
            .removeSuffix(";")
            .replace('/', '.')
        if (normalized.isEmpty() || normalized.contains("..")) return null
        val parts = normalized.split('.').filter(String::isNotEmpty)
        if (parts.isEmpty()) return null
        if (parts.any { part -> part.any { it == '/' || it == '\\' || it == ':' } }) return null
        return parts.joinToString("/") + ".java"
    }

    private fun sanitize(value: String): String =
        value.map { char -> if (char.isLetterOrDigit() || char == '.' || char == '-' || char == '_') char else '_' }
            .joinToString("")
}
