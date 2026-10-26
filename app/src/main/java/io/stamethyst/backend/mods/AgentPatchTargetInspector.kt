package io.stamethyst.backend.mods

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceMethodVisitor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

data class AgentPatchMethodSignature(
    val name: String,
    val descriptor: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val isStatic: Boolean,
    /** True when the method carries bytecode instructions (not abstract or native). */
    val hasCode: Boolean = true,
    /** Entry names from the LocalVariableTable, when the class has debug information. */
    val localVariables: List<AgentPatchLocalVariable> = emptyList(),
    /** javap-style bytecode listing, populated only when inspection asks for it. */
    val bytecode: String? = null,
    /** True when [bytecode] hit the per-method output cap. */
    val bytecodeTruncated: Boolean = false,
) {
    val displayName: String
        get() = "$name(${parameterTypes.joinToString(", ")}) : $returnType"
}

/** One LocalVariableTable entry exposed separately so truncation cannot hide parameter names. */
data class AgentPatchLocalVariable(
    val name: String,
    val type: String,
    val slot: Int,
)

data class AgentPatchFieldSignature(
    val name: String,
    val type: String,
    val isStatic: Boolean,
)

data class AgentPatchTargetInspection(
    val targetClassName: String,
    val memberName: String,
    val classOrigin: String,
    val overloads: List<AgentPatchMethodSignature>,
    val fields: List<AgentPatchFieldSignature>,
)

data class AgentPatchSkeleton(
    val file: File,
    val targetClassName: String,
    val memberName: String,
    val descriptor: String,
    val source: String,
)

/** Reads exact target signatures from class files without loading game classes into Android. */
object AgentPatchTargetInspector {
    private const val MAX_FIELDS = 100

    /** Per-method bytecode cap; the full result is also bounded by the gateway tool-result limit. */
    private const val MAX_BYTECODE_CHARS = 8_000

    fun inspectTarget(
        targetClassName: String,
        memberName: String,
        classpath: List<File>,
        includeBytecode: Boolean = false,
    ): AgentPatchTargetInspection {
        val normalizedClassName = normalizeClassName(targetClassName)
        require(normalizedClassName.isNotEmpty()) { "target_class is required" }
        val normalizedMemberName = normalizeMemberName(memberName)
        require(normalizedMemberName.isNotEmpty()) { "member is required" }

        val lookup = ClasspathLookup(classpath)
        val classBytes = lookup.readClass(normalizedClassName)
            ?: throw IllegalArgumentException("Target class not found on the compile classpath: $normalizedClassName")
        val classNode = readClassNode(classBytes)
        val methods = classNode.methods.orEmpty()
            .asSequence()
            .filter { method ->
                when (normalizedMemberName) {
                    CONSTRUCTOR -> method.name == "<init>"
                    STATIC_INITIALIZER -> method.name == "<clinit>"
                    else -> method.name == normalizedMemberName
                }
            }
            .map { method -> toSignature(method, includeBytecode) }
            .toList()
        if (normalizedMemberName != CLASS_INITIALIZER && methods.isEmpty()) {
            throw IllegalArgumentException(
                "Target member not found: $normalizedClassName#$normalizedMemberName",
            )
        }
        return AgentPatchTargetInspection(
            targetClassName = normalizedClassName,
            memberName = normalizedMemberName,
            classOrigin = lookup.origin(normalizedClassName),
            overloads = methods,
            fields = classNode.fields.orEmpty()
                .take(MAX_FIELDS)
                .map { field ->
                    AgentPatchFieldSignature(
                        name = field.name,
                        type = Type.getType(field.desc).className,
                        isStatic = field.access and Opcodes.ACC_STATIC != 0,
                    )
                },
        )
    }

    fun generateSkeleton(
        patchRoot: File,
        packageName: String,
        patchClassName: String,
        targetClassName: String,
        memberName: String,
        overloadIndex: Int?,
        patchKind: String,
        classpath: List<File>,
        overwrite: Boolean,
    ): AgentPatchSkeleton {
        require(packageName.matches(PACKAGE_PATTERN)) { "Invalid package_name." }
        require(patchClassName.matches(CLASS_PATTERN)) { "Invalid class_name." }
        val kind = patchKind.trim().lowercase(Locale.ROOT)
        require(kind == "prefix" || kind == "postfix") { "patch_kind must be prefix or postfix." }

        val inspection = inspectTarget(targetClassName, memberName, classpath)
        require(inspection.memberName != CLASS_INITIALIZER) {
            "Patch skeleton generation does not support <class>; patch a concrete method instead."
        }
        val selected = when {
            inspection.overloads.size == 1 && overloadIndex == null -> inspection.overloads.single()
            overloadIndex != null -> inspection.overloads.getOrNull(overloadIndex)
            else -> null
        } ?: throw IllegalArgumentException(
            "Target has ${inspection.overloads.size} overloads; provide overload_index from inspect_agent_patch_target.",
        )

        val relativeDirectory = packageName.replace('.', '/')
        val outputFile = File(patchRoot, "src/$relativeDirectory/$patchClassName.java").canonicalFile
        val canonicalRoot = File(patchRoot, "src").canonicalFile
        require(outputFile.toPath().startsWith(canonicalRoot.toPath())) { "Generated file escapes patch_source/src/." }
        if (outputFile.exists() && !overwrite) {
            throw IllegalArgumentException("Generated source already exists: ${outputFile.relativeTo(patchRoot)}")
        }
        outputFile.parentFile?.mkdirs()
        val source = buildSkeletonSource(
            packageName = packageName,
            patchClassName = patchClassName,
            inspection = inspection,
            selected = selected,
            patchKind = kind,
        )
        outputFile.writeText(source, Charsets.UTF_8)
        return AgentPatchSkeleton(
            file = outputFile,
            targetClassName = inspection.targetClassName,
            memberName = inspection.memberName,
            descriptor = selected.descriptor,
            source = source,
        )
    }

    internal fun normalizeClassName(raw: String): String = raw
        .trim()
        .removePrefix("L")
        .removeSuffix(";")
        .replace('/', '.')

    internal fun normalizeMemberName(raw: String): String = when (raw.trim()) {
        "constructor", "CONSTRUCTOR", "<init>" -> CONSTRUCTOR
        "static initializer", "STATICINITIALIZER", "<clinit>" -> STATIC_INITIALIZER
        "class", "CLASS", "<class>" -> CLASS_INITIALIZER
        else -> raw.trim()
    }

    internal fun readClassNode(classBytes: ByteArray): ClassNode = ClassNode().also { node ->
        ClassReader(classBytes).accept(node, 0)
    }

    internal fun normalizeTypeName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            return runCatching { Type.getType(trimmed).className }
                .getOrDefault(trimmed)
                .replace('$', '.')
        }
        return trimmed
            .removePrefix("L")
            .removeSuffix(";")
            .replace('/', '.')
            .replace('$', '.')
    }

    private fun toSignature(method: MethodNode, includeBytecode: Boolean): AgentPatchMethodSignature {
        val argumentTypes = Type.getArgumentTypes(method.desc).map { it.className.replace('$', '.') }
        val bytecode = if (includeBytecode) disassembleMethod(method) else null
        val localVariables = if (includeBytecode) {
            method.localVariables.orEmpty()
                .map { local ->
                    AgentPatchLocalVariable(
                        name = local.name,
                        type = Type.getType(local.desc).className.replace('$', '.'),
                        slot = local.index,
                    )
                }
                .sortedBy { it.slot }
        } else {
            emptyList()
        }
        return AgentPatchMethodSignature(
            name = when (method.name) {
                "<init>" -> CONSTRUCTOR
                "<clinit>" -> STATIC_INITIALIZER
                else -> method.name
            },
            descriptor = method.desc,
            parameterTypes = argumentTypes,
            returnType = Type.getReturnType(method.desc).className.replace('$', '.'),
            isStatic = method.access and Opcodes.ACC_STATIC != 0,
            hasCode = method.instructions.size() > 0,
            localVariables = localVariables,
            bytecode = bytecode?.first,
            bytecodeTruncated = bytecode?.second ?: false,
        )
    }

    /**
     * Renders one method with ASM's Textifier, including the LocalVariableTable entries when the
     * class was compiled with debug information. Bounded so a huge method cannot crowd out the
     * rest of the inspection result.
     */
    private fun disassembleMethod(method: MethodNode): Pair<String, Boolean> {
        val textifier = Textifier()
        runCatching { method.accept(TraceMethodVisitor(textifier)) }
            .getOrElse { error ->
                return "bytecode_unavailable: ${error.message ?: error.javaClass.simpleName}" to false
            }
        val writer = StringWriter()
        val printer = PrintWriter(writer)
        textifier.print(printer)
        printer.flush()
        val text = writer.toString().trim()
        val header = "// method ${method.name}${method.desc}"
        val listing = if (text.isEmpty()) header else "$header\n$text"
        if (listing.length <= MAX_BYTECODE_CHARS) return listing to false
        return listing.take(MAX_BYTECODE_CHARS) + "\n... (truncated)" to true
    }

    private fun buildSkeletonSource(
        packageName: String,
        patchClassName: String,
        inspection: AgentPatchTargetInspection,
        selected: AgentPatchMethodSignature,
        patchKind: String,
    ): String {
        val targetSourceName = sourceTypeName(inspection.targetClassName)
        val annotationMethod = inspection.memberName
        val parameterTypes = selected.parameterTypes.joinToString(", ") { "${sourceTypeName(it)}.class" }
        val patchAnnotation = if (parameterTypes.isEmpty()) {
            "paramtypez = {}"
        } else {
            "paramtypez = { $parameterTypes }"
        }
        val methodAnnotation = if (patchKind == "prefix") "SpirePrefixPatch" else "SpirePostfixPatch"
        val methodName = if (patchKind == "prefix") "Prefix" else "Postfix"
        val methodParameters = buildList {
            if (patchKind == "postfix" && selected.returnType != "void") {
                add("${sourceTypeName(selected.returnType)} __result")
            }
            if (!selected.isStatic && inspection.memberName != CONSTRUCTOR) {
                add("$targetSourceName __instance")
            }
            if (selected.parameterTypes.isNotEmpty()) {
                add("Object[] __args")
            }
        }.joinToString(", ")
        val returnStatement = if (patchKind == "postfix" && selected.returnType != "void") {
            "        return __result;\n"
        } else {
            ""
        }
        return """
            package $packageName;

            import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
            import com.evacipated.cardcrawl.modthespire.lib.$methodAnnotation;

            /** Generated from $inspection.targetClassName#$annotationMethod ${selected.descriptor}. */
            @SpirePatch2(
                cls = "${inspection.targetClassName}",
                method = "$annotationMethod",
                $patchAnnotation
            )
            public final class $patchClassName {
                private $patchClassName() {
                }

                @$methodAnnotation
                public static ${if (patchKind == "postfix" && selected.returnType != "void") sourceTypeName(selected.returnType) else "void"} $methodName($methodParameters) {
                    // TODO: implement the requested behavior. __args contains target arguments by position.
$returnStatement                }
            }
        """.trimIndent() + "\n"
    }

    private fun sourceTypeName(typeName: String): String = typeName.replace('$', '.')

    private class ClasspathLookup(private val entries: List<File>) {
        private val bytesCache = HashMap<String, ByteArray?>()
        private val origins = HashMap<String, String>()

        fun readClass(className: String): ByteArray? {
            val internalName = className.replace('.', '/')
            if (bytesCache.containsKey(internalName)) return bytesCache[internalName]
            for (entry in entries) {
                val bytes = readFromEntry(entry, internalName) ?: continue
                bytesCache[internalName] = bytes
                origins[internalName] = entry.absolutePath
                return bytes
            }
            bytesCache[internalName] = null
            return null
        }

        fun origin(className: String): String {
            readClass(className)
            return origins[className.replace('.', '/')].orEmpty()
        }

        private fun readFromEntry(entry: File, internalName: String): ByteArray? {
            if (entry.isDirectory) {
                return File(entry, "$internalName.class").takeIf(File::isFile)?.readBytes()
            }
            if (!entry.isFile) return null
            return AgentModJarReader.readClassEntryBytes(entry, internalName)
        }
    }

    private const val CONSTRUCTOR = "<ctor>"
    private const val STATIC_INITIALIZER = "<staticinit>"
    private const val CLASS_INITIALIZER = "<class>"
    private val PACKAGE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val CLASS_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")
}
