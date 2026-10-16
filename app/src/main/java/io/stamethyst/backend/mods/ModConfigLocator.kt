package io.stamethyst.backend.mods

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue
import java.io.File
import java.util.zip.ZipFile

internal data class ModConfigLocationResult(val paths: Set<String>, val incomplete: Boolean)

/** Finds only provable literal arguments, never executes a mod or guesses a directory from its ID. */
internal object ModConfigLocator {
    fun locate(jar: File): ModConfigLocationResult {
        if (!jar.isFile) return ModConfigLocationResult(emptySet(), true)
        val paths = linkedSetOf<String>()
        var incomplete = false
        var bytesRead = 0L
        ZipFile(jar).use { zip ->
            val entries = zip.entries()
            var classes = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.endsWith(".class")) continue
                if (++classes > 10_000 || entry.size !in 0..MAX_CLASS_BYTES || bytesRead + entry.size > MAX_TOTAL_BYTES) {
                    incomplete = true
                    continue
                }
                val output = java.io.ByteArrayOutputStream()
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(8192)
                    while (output.size() <= MAX_CLASS_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_CLASS_BYTES + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                if (output.size() > MAX_CLASS_BYTES) {
                    incomplete = true
                    continue
                }
                bytesRead += output.size()
                try {
                    val node = ClassNode()
                    ClassReader(output.toByteArray()).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                    for (method in node.methods) {
                        val instructions = method.instructions.toArray()
                        val calls = instructions.filterIsInstance<MethodInsnNode>().filter { call ->
                            call.owner == SPIRE_CONFIG && (call.name == "<init>" || call.name == "makeFilePath")
                        }
                        if (calls.isEmpty()) continue
                        // The input is an arbitrary third-party JAR. Bound analysis frame allocation.
                        if (method.maxStack > 256 || method.maxLocals > 512 || instructions.size > 8000) {
                            incomplete = true
                            continue
                        }
                        val interpreter = object : SourceInterpreter(Opcodes.ASM9) {
                            override fun copyOperation(insn: AbstractInsnNode, value: SourceValue): SourceValue = value
                        }
                        val frames = Analyzer(interpreter).analyze(node.name, method)
                        for (call in calls) {
                            val arguments = Type.getArgumentTypes(call.desc)
                            val frame = frames[instructions.indexOf(call)]
                            if (frame == null || arguments.size < 2 || arguments[0].descriptor != "Ljava/lang/String;" ||
                                arguments[1].descriptor != "Ljava/lang/String;") {
                                incomplete = true
                                continue
                            }
                            val offset = frame.stackSize - arguments.size
                            val directory = literal(frame.getStack(offset))
                            val filename = literal(frame.getStack(offset + 1))
                            val extension = if (call.name == "makeFilePath" && arguments.size == 3) {
                                literal(frame.getStack(offset + 2))
                            } else Literal(true, "properties")
                            val path = if (directory.known && filename.known && filename.value != null &&
                                extension.known && extension.value == "properties") {
                                safePath(directory.value, filename.value)
                            } else null
                            if (path != null) paths += path else incomplete = true
                        }
                    }
                } catch (_: Exception) {
                    incomplete = true
                }
            }
        }
        return ModConfigLocationResult(paths, incomplete)
    }

    private data class Literal(val known: Boolean, val value: String?)

    private fun literal(source: SourceValue): Literal {
        val values = source.insns.map { instruction ->
            when {
                instruction is LdcInsnNode && instruction.cst is String -> Literal(true, instruction.cst as String)
                instruction.opcode == Opcodes.ACONST_NULL -> Literal(true, null)
                else -> Literal(false, null)
            }
        }
        return values.firstOrNull()?.takeIf { first -> first.known && values.all { it == first } } ?: Literal(false, null)
    }

    private fun safePath(directory: String?, name: String): String? {
        val path = if (directory == null) "$name.properties" else "$directory/$name.properties"
        if (path.startsWith('/') || path.contains('\\') || path.contains('\u0000') ||
            path.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return path
    }

    private const val SPIRE_CONFIG = "com/evacipated/cardcrawl/modthespire/lib/SpireConfig"
    private const val MAX_CLASS_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
}
