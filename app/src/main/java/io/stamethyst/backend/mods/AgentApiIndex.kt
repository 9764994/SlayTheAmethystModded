package io.stamethyst.backend.mods

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** One type found on the compile classpath, without its members. */
data class ApiTypeSummary(
    val binaryName: String,
    val kind: String,
    val superName: String,
    val interfaces: List<String>,
    val isPublic: Boolean,
    val isNested: Boolean,
    val origin: String,
) {
    val simpleName: String get() = binaryName.substringAfterLast('.')
}

/** One constructor, method, or field of an indexed type. */
data class ApiMember(
    val kind: String,
    val name: String,
    val declaration: String,
    val isStatic: Boolean,
)

data class ApiTypeDetail(
    val summary: ApiTypeSummary,
    val constructors: List<ApiMember>,
    val methods: List<ApiMember>,
    val fields: List<ApiMember>,
)

/**
 * Read-only symbol index over the game/mod compile classpath.
 *
 * The parent mod's JAR is bytecode and the game API is not documented by a machine-readable
 * descriptor, so the agent needs a way to discover exact type and member signatures instead of
 * recalling them. Signatures in this index come straight from the installed bytecode, so they are
 * always the truth for the version the resulting patch will run against.
 *
 * The full index stores only type headers (name, kind, super type, interfaces, origin); members are
 * parsed on demand so indexing thousands of classes stays cheap. The result is cached per classpath
 * signature, because the classpath does not change within an editing session.
 */
object AgentApiIndex {
    private const val MAX_ENTRIES = 200_000
    private const val MAX_MEMORY_INDEXES = 4
    private const val MAX_DISK_INDEXES = 4

    /** How many classes to index between interruption checks, so cancel takes effect promptly. */
    private const val CANCELLATION_CHECK_INTERVAL = 64
    private const val INDEX_CACHE_DIR = "agent-api-index"
    private const val INDEX_MAGIC = 0x41504958
    private const val INDEX_VERSION = 1
    private val cache = ConcurrentHashMap<String, Index>()

    /**
     * Builds or loads the index for [classpath], optionally persisting it under [indexDir] so a new
     * process does not re-parse thousands of class headers. The in-memory entry is still keyed by the
     * full classpath signature; the disk entry is keyed by its SHA-256.
     */
    fun index(classpath: List<File>, indexDir: File? = null): Index {
        val signature = signatureOf(classpath)
        if (cache.size > MAX_MEMORY_INDEXES) cache.clear()
        return cache.computeIfAbsent(signature) {
            val directory = indexDir?.takeIf { it.isDirectory || it.mkdirs() }
            val file = directory?.let { File(it, sha256(signature) + ".idx") }
            val stored = file?.let(::readIndex)
            if (stored != null) {
                return@computeIfAbsent stored
            }
            val built = build(classpath)
            if (file != null && directory != null) {
                writeIndex(file, built)
                pruneIndexes(directory)
            }
            built
        }
    }

    /** Resolves the persistent index directory for [context] and loads or builds the index. */
    fun index(context: android.content.Context, classpath: List<File>): Index =
        index(classpath, AgentPatchSourceCompiler.resolveAgentPatchCacheDir(context, INDEX_CACHE_DIR))

    private fun signatureOf(classpath: List<File>): String =
        classpath.joinToString("|") { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }

    private fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }

    /** Drops the in-memory indexes so the next call must go to disk. Test seam. */
    internal fun clearMemoryCache() {
        cache.clear()
    }

    internal fun readIndex(file: File): Index? {
        if (!file.isFile) return null
        val index = runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != INDEX_MAGIC) return@use null
                if (input.readInt() != INDEX_VERSION) return@use null
                val count = input.readInt()
                if (count < 0 || count > MAX_ENTRIES) return@use null
                val types = LinkedHashMap<String, ApiTypeSummary>(count)
                repeat(count) {
                    val binaryName = input.readUTF()
                    val kind = input.readUTF()
                    val superName = input.readUTF()
                    val origin = input.readUTF()
                    val flags = input.readByte().toInt()
                    val interfaceCount = input.readInt().coerceAtLeast(0)
                    val interfaces = ArrayList<String>(interfaceCount)
                    repeat(interfaceCount) { interfaces += input.readUTF() }
                    types[binaryName] = ApiTypeSummary(
                        binaryName = binaryName,
                        kind = kind,
                        superName = superName,
                        interfaces = interfaces,
                        isPublic = flags and 1 != 0,
                        isNested = flags and 2 != 0,
                        origin = origin,
                    )
                }
                Index(types)
            }
        }.getOrNull()
        // A rejected entry is either corrupt or written by an older format; drop it so the next
        // build can replace it without a stale file shadowing the fresh index.
        if (index == null) runCatching { file.delete() }
        return index
    }

    internal fun writeIndex(file: File, index: Index) {
        val temporary = File(file.parentFile, "${file.name}.${System.nanoTime()}.${Thread.currentThread().id}.tmp")
        val written = runCatching {
            java.io.DataOutputStream(java.io.BufferedOutputStream(temporary.outputStream())).use { output ->
                output.writeInt(INDEX_MAGIC)
                output.writeInt(INDEX_VERSION)
                output.writeInt(index.entries.size)
                index.entries.values.forEach { type ->
                    output.writeUTF(type.binaryName)
                    output.writeUTF(type.kind)
                    output.writeUTF(type.superName)
                    output.writeUTF(type.origin)
                    var flags = 0
                    if (type.isPublic) flags = flags or 1
                    if (type.isNested) flags = flags or 2
                    output.writeByte(flags)
                    output.writeInt(type.interfaces.size)
                    type.interfaces.forEach(output::writeUTF)
                }
            }
            if (file.exists() && !file.delete()) return@runCatching false
            temporary.renameTo(file)
        }.getOrDefault(false)
        if (!written) temporary.delete()
    }

    private fun pruneIndexes(directory: File) {
        val files = directory.listFiles { file -> file.isFile && file.extension == "idx" } ?: return
        if (files.size <= MAX_DISK_INDEXES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_INDEXES)
            .forEach { runCatching { it.delete() } }
    }

    /**
     * Builds the type index.
     *
     * Each archive is opened once and every class header is read through that single handle.
     * Opening a `ZipFile` parses the entire central directory, so reopening it per class turned this
     * into quadratic work over the game JAR's thousands of entries and made the tool look hung.
     *
     * The loop polls for interruption so cancelling a run stops indexing instead of waiting for the
     * whole classpath to be scanned.
     */
    private fun build(classpath: List<File>): Index {
        val types = LinkedHashMap<String, ApiTypeSummary>()
        var seen = 0
        for (entry in classpath) {
            checkInterrupted()
            if (!entry.exists()) continue
            val origin = entry.name
            if (entry.isDirectory) {
                for (internalName in directoryClassNames(entry)) {
                    if (seen >= MAX_ENTRIES) break
                    seen++
                    if (seen % CANCELLATION_CHECK_INTERVAL == 0) checkInterrupted()
                    val summary = readSummary(entry, internalName, origin) ?: continue
                    types.putIfAbsent(summary.binaryName, summary)
                }
                continue
            }
            if (!entry.isFile || !entry.name.endsWith(".jar", ignoreCase = true)) continue
            val indexedFromJar = runCatching {
                ZipFile(entry).use { zip ->
                    for (internalName in AgentModJarReader.classEntries(zip)) {
                        if (seen >= MAX_ENTRIES) break
                        seen++
                        if (seen % CANCELLATION_CHECK_INTERVAL == 0) checkInterrupted()
                        val summary = readSummary(zip, internalName, origin) ?: continue
                        types.putIfAbsent(summary.binaryName, summary)
                    }
                    true
                }
            }.getOrDefault(false)
            if (!indexedFromJar) {
                // Android's ZipFile rejects duplicate entry names. Fall back to the sequential
                // reader for this archive only, rather than reopening it once per class.
                for (internalName in AgentModJarReader.classEntries(entry)) {
                    if (seen >= MAX_ENTRIES) break
                    seen++
                    if (seen % CANCELLATION_CHECK_INTERVAL == 0) checkInterrupted()
                    val summary = readSummary(entry, internalName, origin) ?: continue
                    types.putIfAbsent(summary.binaryName, summary)
                }
            }
        }
        return Index(types)
    }

    /** Fails the build as cancelled instead of finishing a scan nobody is waiting for. */
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException("API index build cancelled")
        }
    }

    private fun directoryClassNames(entry: File): List<String> {
        val root = entry.canonicalFile.toPath()
        return entry.walkTopDown()
            .filter { it.isFile && it.extension.equals("class", ignoreCase = true) }
            .map { it.canonicalFile.toPath().let { path -> root.relativize(path).toString() } }
            .map { it.removeSuffix(".class").replace(File.separatorChar, '/') }
            .toList()
    }

    private fun readSummary(zip: ZipFile, internalName: String, origin: String): ApiTypeSummary? {
        val bytes = AgentModJarReader.readClassEntryBytes(zip, internalName) ?: return null
        return summarize(bytes, internalName, origin)
    }

    private fun readSummary(entry: File, internalName: String, origin: String): ApiTypeSummary? {
        val bytes = readClassBytes(entry, internalName) ?: return null
        return summarize(bytes, internalName, origin)
    }

    private fun summarize(bytes: ByteArray, internalName: String, origin: String): ApiTypeSummary? {
        var access = 0
        var name = internalName
        var superName = ""
        val interfaces = ArrayList<String>()
        runCatching {
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(
                        version: Int,
                        visitedAccess: Int,
                        visitedName: String,
                        signature: String?,
                        visitedSuperName: String?,
                        visitedInterfaces: Array<out String>?,
                    ) {
                        access = visitedAccess
                        name = visitedName
                        superName = visitedSuperName.orEmpty()
                        visitedInterfaces.orEmpty().forEach(interfaces::add)
                    }

                    // Members are read on demand; returning null keeps this pass cheap.
                    override fun visitMethod(
                        access: Int,
                        name: String?,
                        descriptor: String?,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ) = null
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        }.getOrNull() ?: return null
        return ApiTypeSummary(
            binaryName = name.replace('/', '.').replace('$', '.'),
            kind = kindOf(access),
            superName = superName.replace('/', '.').replace('$', '.'),
            interfaces = interfaces.map { it.replace('/', '.').replace('$', '.') },
            isPublic = access and Opcodes.ACC_PUBLIC != 0,
            isNested = name.contains('$'),
            origin = origin,
        )
    }
    private fun readClassBytes(entry: File, internalName: String): ByteArray? {
        if (entry.isDirectory) {
            return File(entry, "$internalName.class").takeIf(File::isFile)?.readBytes()
        }
        if (!entry.isFile) return null
        return AgentModJarReader.readClassEntryBytes(entry, internalName)
    }

    private fun kindOf(access: Int): String = when {
        access and Opcodes.ACC_ANNOTATION != 0 -> "annotation"
        access and Opcodes.ACC_INTERFACE != 0 -> "interface"
        access and Opcodes.ACC_ENUM != 0 -> "enum"
        else -> "class"
    }

    /** Searches type names, and optionally restricts to types implementing a given interface. */
    fun search(
        types: Collection<ApiTypeSummary>,
        query: String?,
        implementsFilter: String?,
        limit: Int,
    ): List<ApiTypeSummary> {
        val needle = query?.trim()?.lowercase().orEmpty()
        val interfaceNeedle = implementsFilter?.trim()?.lowercase()?.replace('$', '.')
        val scored = ArrayList<Pair<Int, ApiTypeSummary>>()
        types.forEach { type ->
            // Compiler-generated members (anonymous classes, lambdas) are never useful to the agent.
            if (type.simpleName.firstOrNull()?.isDigit() == true) return@forEach
            if (interfaceNeedle != null &&
                type.interfaces.none { it.lowercase().endsWith(interfaceNeedle) || it.lowercase() == interfaceNeedle }
            ) {
                return@forEach
            }
            if (needle.isNotEmpty()) {
                val score = score(type, needle) ?: return@forEach
                scored += score to type
            } else {
                scored += 0 to type
            }
        }
        return scored
            .sortedWith(compareByDescending<Pair<Int, ApiTypeSummary>> { it.first }.thenBy { it.second.binaryName })
            .take(limit.coerceIn(1, MAX_SEARCH_LIMIT))
            .map { it.second }
    }

    private fun score(type: ApiTypeSummary, needle: String): Int? {
        val simple = type.simpleName.lowercase()
        val full = type.binaryName.lowercase()
        val base = when {
            simple == needle -> 100
            simple.startsWith(needle) -> 60
            simple.contains(needle) -> 40
            full.contains(needle) -> 20
            else -> return null
        }
        var score = base
        if (type.isPublic) score += 10
        if (!type.isNested) score += 8
        if (PREFERRED_PACKAGES.any { full.startsWith(it) }) score += 15
        if (type.origin.startsWith("BaseMod") || type.origin.startsWith("StSLib")) score += 5
        return score
    }

    /** Reads one type's public and protected surface from the classpath. */
    fun describe(classpath: List<File>, binaryName: String, memberFilter: String?): ApiTypeDetail? {
        val internal = resolveInternalName(classpath, binaryName) ?: return null
        val entry = findEntry(classpath, internal) ?: return null
        val bytes = readClassBytes(entry, internal) ?: return null
        val node = AgentPatchTargetInspector.readClassNode(bytes)
        val name = node.name.replace('/', '.').replace('$', '.')
        val summary = ApiTypeSummary(
            binaryName = name,
            kind = kindOf(node.access),
            superName = node.superName.orEmpty().replace('/', '.').replace('$', '.'),
            interfaces = node.interfaces.orEmpty().map { it.replace('/', '.').replace('$', '.') },
            isPublic = node.access and Opcodes.ACC_PUBLIC != 0,
            isNested = node.name.contains('$'),
            origin = entry.name,
        )
        val filter = memberFilter?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        val constructors = ArrayList<ApiMember>()
        val methods = ArrayList<ApiMember>()
        val fields = ArrayList<ApiMember>()
        node.methods.orEmpty().forEach { method ->
            if (method.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) == 0) return@forEach
            if (method.name.startsWith("<") && method.name != "<init>") return@forEach
            if (filter != null && !method.name.lowercase().contains(filter)) return@forEach
            val member = ApiMember(
                kind = if (method.name == "<init>") "constructor" else "method",
                name = if (method.name == "<init>") simpleNameOf(name) else method.name,
                declaration = describeMethod(name, method.name, method.desc, method.access),
                isStatic = method.access and Opcodes.ACC_STATIC != 0,
            )
            if (method.name == "<init>") constructors += member else methods += member
        }
        node.fields.orEmpty().forEach { field ->
            if (field.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) == 0) return@forEach
            if (filter != null && !field.name.lowercase().contains(filter)) return@forEach
            fields += ApiMember(
                kind = "field",
                name = field.name,
                declaration = "${if (field.access and Opcodes.ACC_STATIC != 0) "static " else ""}" +
                    "${javaType(Type.getType(field.desc))} ${field.name}",
                isStatic = field.access and Opcodes.ACC_STATIC != 0,
            )
        }
        return ApiTypeDetail(summary, constructors, methods, fields)
    }

    private fun describeMethod(owner: String, name: String, desc: String, access: Int): String {
        val arguments = Type.getArgumentTypes(desc).joinToString(", ") { javaType(it) }
        val modifiers = buildString {
            if (access and Opcodes.ACC_STATIC != 0) append("static ")
            if (access and Opcodes.ACC_ABSTRACT != 0) append("abstract ")
        }
        return if (name == "<init>") {
            "$modifiers${simpleNameOf(owner)}($arguments)"
        } else {
            "$modifiers${javaType(Type.getReturnType(desc))} $name($arguments)"
        }
    }

    private fun simpleNameOf(binaryName: String): String = binaryName.substringAfterLast('.')

    internal fun javaType(type: Type): String = when (type.sort) {
        Type.ARRAY -> javaType(type.elementType) + "[]"
        else -> type.className.replace('$', '.')
    }

    /**
     * Resolves a dotted binary name to an internal name, allowing nested types to be written either
     * as `AbstractCard$CardColor` or as the source form `AbstractCard.CardColor`.
     */
    private fun resolveInternalName(classpath: List<File>, binaryName: String): String? {
        val raw = binaryName.trim()
        val normalized = if (raw.startsWith("L") && raw.endsWith(";")) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }.replace('/', '.')
        val parts = normalized.split('.').filter(String::isNotEmpty)
        if (parts.isEmpty()) return null
        for (split in parts.size - 1 downTo 0) {
            val internal = (parts.subList(0, split) + parts.subList(split, parts.size).joinToString("$"))
                .joinToString("/")
            if (findEntry(classpath, internal) != null) return internal
        }
        return null
    }

    private fun findEntry(classpath: List<File>, internalName: String): File? =
        classpath.firstOrNull { entry ->
            when {
                entry.isDirectory -> File(entry, "$internalName.class").isFile
                entry.isFile && entry.name.endsWith(".jar", ignoreCase = true) ->
                    AgentModJarReader.hasClassEntry(entry, internalName)
                else -> false
            }
        }

    private val PREFERRED_PACKAGES = listOf(
        "basemod.",
        "com.evacipated.cardcrawl.mod.stslib.",
        "com.megacrit.cardcrawl.",
        "com.evacipated.cardcrawl.modthespire.",
    )

    const val MAX_SEARCH_LIMIT = 100

    /** Immutable light index of every type on a resolved classpath. */
    class Index internal constructor(private val types: Map<String, ApiTypeSummary>) {
        val size: Int get() = types.size

        internal val entries: Map<String, ApiTypeSummary> get() = types

        fun all(): Collection<ApiTypeSummary> = types.values
    }
}
