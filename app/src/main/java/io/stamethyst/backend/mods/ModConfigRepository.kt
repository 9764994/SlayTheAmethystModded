package io.stamethyst.backend.mods

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties

internal data class ModConfigFile(val relativePath: String, val recommended: Boolean)

internal enum class ModConfigValueType {
    BOOLEAN, INTEGER, DECIMAL, TEXT;

    companion object {
        // Properties have no schema. These are editing hints, not mod-defined constraints.
        fun infer(value: String): ModConfigValueType = when {
            value == "true" || value == "false" -> BOOLEAN
            value.matches(Regex("[+-]?\\d+")) -> INTEGER
            value.matches(Regex("[+-]?(?:\\d+\\.\\d*|\\d*\\.\\d+|\\d+)(?:[eE][+-]?\\d+)?")) -> DECIMAL
            else -> TEXT
        }
    }

    fun accepts(value: String): Boolean = when (this) {
        BOOLEAN -> value == "true" || value == "false"
        INTEGER -> value.matches(Regex("[+-]?\\d+"))
        DECIMAL -> value.toBigDecimalOrNull() != null
        TEXT -> true
    }
}

internal data class ModConfigEntry(val key: String, val value: String) {
    val type: ModConfigValueType = ModConfigValueType.infer(value)
}

internal class ModConfigDocument(
    val relativePath: String,
    internal val originalBytes: ByteArray,
    val entries: List<ModConfigEntry>,
)

/** Reads the same ISO-8859-1 / escaped-Unicode format as SpireConfig's Properties.load(InputStream). */
internal class ModConfigRepository(private val root: File) {
    fun discover(identities: List<String>): List<ModConfigFile> {
        if (!root.exists()) return emptyList()
        val names = identities.map(::normalize).filter(String::isNotEmpty).toSet()
        val result = mutableListOf<ModConfigFile>()
        fun visit(directory: File, depth: Int) {
            val children = directory.listFiles() ?: throw IOException("Cannot read configuration directory")
            for (file in children.sortedBy { it.name }) {
                // Never follow symlinks, including ones pointing back into this tree.
                if (Files.isSymbolicLink(file.toPath())) continue
                if (file.isDirectory && depth < MAX_DEPTH) {
                    visit(file, depth + 1)
                } else if (file.isFile && file.extension.equals("properties", ignoreCase = true)) {
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    resolve(relative)
                    val components = relative.split('/').dropLast(1) + file.nameWithoutExtension
                    result += ModConfigFile(relative, components.any { normalize(it) in names })
                    if (result.size > MAX_FILES) throw IOException("Too many configuration files (limit: $MAX_FILES)")
                }
            }
        }
        visit(root, 0)
        return result.sortedWith(compareByDescending<ModConfigFile> { it.recommended }.thenBy { it.relativePath })
    }

    fun load(relativePath: String): ModConfigDocument {
        val bytes = readBounded(resolve(relativePath))
        val properties = parse(bytes.toString(Charsets.ISO_8859_1))
        if (properties.size > MAX_ENTRIES) throw IOException("Too many configuration entries (limit: $MAX_ENTRIES)")
        return ModConfigDocument(
            relativePath,
            bytes,
            properties.stringPropertyNames().sorted().map { ModConfigEntry(it, properties.getProperty(it)) },
        )
    }

    fun save(document: ModConfigDocument, values: Map<String, String>): ModConfigDocument {
        val original = document.entries.associate { it.key to it.value }
        require(values.keys == original.keys) { "Configuration keys cannot be added or removed" }
        require(document.entries.all { it.type.accepts(values.getValue(it.key)) }) { "Invalid configuration value" }
        val file = resolve(document.relativePath)
        checkUnchanged(file, document.originalBytes)
        if (values == original) return document
        val source = document.originalBytes.toString(Charsets.ISO_8859_1)
        val records = records(source)
        val lastRecordByKey = mutableMapOf<String, Int>()
        records.forEachIndexed { index, record ->
            parse(record).stringPropertyNames().forEach { lastRecordByKey[it] = index }
        }
        val replacements = mutableMapOf<Int, String>()
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        values.filter { (key, value) -> original[key] != value }.forEach { (key, value) ->
            val index = lastRecordByKey[key] ?: throw IOException("Configuration entry disappeared: $key")
            val encoded = java.io.ByteArrayOutputStream().also { output ->
                Properties().apply { setProperty(key, value) }.store(output, null)
            }.toByteArray().toString(Charsets.ISO_8859_1)
                .lineSequence().filter { it.isNotEmpty() && !it.startsWith('#') }.joinToString(newline)
            val ending = records[index].let {
                when {
                    it.endsWith("\r\n") -> "\r\n"
                    it.endsWith('\n') -> "\n"
                    it.endsWith('\r') -> "\r"
                    else -> ""
                }
            }
            replacements[index] = encoded + ending
        }
        val updated = records.mapIndexed { index, record -> replacements[index] ?: record }
            .joinToString("").toByteArray(Charsets.ISO_8859_1)
        if (updated.size > MAX_BYTES) throw IOException("Configuration file exceeds $MAX_BYTES bytes")
        // Validate the complete round trip before touching any file.
        check(parse(updated.toString(Charsets.ISO_8859_1)).let { parsed ->
            values.all { (key, value) -> parsed.getProperty(key) == value }
        }) { "Configuration could not be encoded safely" }
        val backup = resolve(document.relativePath + ".bak", allowBackup = true)
        atomicWrite(backup, document.originalBytes)
        checkUnchanged(file, document.originalBytes)
        atomicWrite(file, updated)
        return ModConfigDocument(document.relativePath, updated, values.toSortedMap().map { ModConfigEntry(it.key, it.value) })
    }

    private fun resolve(relativePath: String, allowBackup: Boolean = false): File {
        val relative = File(relativePath)
        require(!relative.isAbsolute && relativePath.split('/', '\\').none { it == ".." }) { "Invalid configuration path" }
        require(relativePath.endsWith(if (allowBackup) ".properties.bak" else ".properties", ignoreCase = true))
        val file = File(root, relativePath)
        val canonicalRoot = root.canonicalFile.toPath()
        require(file.canonicalFile.toPath().startsWith(canonicalRoot) && file.canonicalFile != root.canonicalFile) {
            "Configuration is outside the configuration directory"
        }
        var component: File? = file
        while (component != null && component != root) {
            require(!Files.isSymbolicLink(component.toPath())) { "Symbolic links are not supported" }
            component = component.parentFile
        }
        return file
    }

    private fun checkUnchanged(file: File, expected: ByteArray) {
        if (!file.isFile || !readBounded(file).contentEquals(expected)) throw ModConfigConflictException()
    }

    private fun readBounded(file: File): ByteArray = file.inputStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        if (bytes.size > MAX_BYTES) throw IOException("Configuration file exceeds $MAX_BYTES bytes")
        bytes
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temp = File.createTempFile(".mod-config-", ".tmp", file.parentFile)
        try {
            temp.outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun parse(text: String): Properties = Properties().apply {
        load(text.byteInputStream(Charsets.ISO_8859_1))
    }

    /** Keep physical formatting, comments, duplicate keys and continuation lines for untouched entries. */
    private fun records(text: String): List<String> {
        val lines = Regex("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").findAll(text).map { it.value }.filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        var current = ""
        var continuing = false
        for (line in lines) {
            current += line
            val body = line.trimEnd('\r', '\n')
            val comment = !continuing && body.trimStart(' ', '\t', '\u000c').let { it.startsWith('#') || it.startsWith('!') }
            continuing = !comment && body.reversed().takeWhile { it == '\\' }.length % 2 == 1
            if (!continuing) {
                result += current
                current = ""
            }
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    companion object {
        private const val MAX_DEPTH = 4
        private const val MAX_FILES = 300
        private const val MAX_ENTRIES = 1000
        private const val MAX_BYTES = 1024 * 1024
    }
}

internal class ModConfigConflictException : IOException("Configuration changed on disk; reopen it before saving")
