package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModConfigLocatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder(File("../agent-tmp").apply { mkdirs() })

    private fun jar(body: MethodVisitor.() -> Unit): File {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Example", null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "configure", "()V", null, null)
        method.visitCode()
        method.body()
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return temporaryFolder.newFile("fixture-${System.nanoTime()}.jar").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("fixture/Example.class"))
                zip.write(writer.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun MethodVisitor.constructor(directory: String?, filename: String, defaults: Boolean = false) {
        visitTypeInsn(Opcodes.NEW, SPIRE_CONFIG)
        visitInsn(Opcodes.DUP)
        if (directory == null) visitInsn(Opcodes.ACONST_NULL) else visitLdcInsn(directory)
        visitLdcInsn(filename)
        if (defaults) {
            visitTypeInsn(Opcodes.NEW, "java/util/Properties")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Properties", "<init>", "()V", false)
        }
        visitMethodInsn(Opcodes.INVOKESPECIAL, SPIRE_CONFIG, "<init>",
            if (defaults) "(Ljava/lang/String;Ljava/lang/String;Ljava/util/Properties;)V"
            else "(Ljava/lang/String;Ljava/lang/String;)V", false)
        visitInsn(Opcodes.POP)
    }

    @Test fun locatesCustomNamesDefaultPropertiesAndRootDirectory() {
        val result = ModConfigLocator.locate(jar {
            constructor("NotTheModId", "preferences", defaults = true)
            constructor(null, "root-config")
        })
        assertEquals(setOf("NotTheModId/preferences.properties", "root-config.properties"), result.paths)
        assertTrue(!result.incomplete)
    }

    @Test fun resolvesLiteralsPassedThroughLocalVariables() {
        val result = ModConfigLocator.locate(jar {
            visitLdcInsn("CustomFolder")
            visitVarInsn(Opcodes.ASTORE, 0)
            visitTypeInsn(Opcodes.NEW, SPIRE_CONFIG)
            visitInsn(Opcodes.DUP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("settings")
            visitMethodInsn(Opcodes.INVOKESPECIAL, SPIRE_CONFIG, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false)
            visitInsn(Opcodes.POP)
        })
        assertEquals(setOf("CustomFolder/settings.properties"), result.paths)
    }

    @Test fun dynamicArgumentsAreNeverGuessedFromNearbyStringLiterals() {
        val result = ModConfigLocator.locate(jar {
            visitLdcInsn("MisleadingModName")
            visitInsn(Opcodes.POP)
            visitTypeInsn(Opcodes.NEW, SPIRE_CONFIG)
            visitInsn(Opcodes.DUP)
            visitLdcInsn("mod.config.directory")
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn("settings")
            visitMethodInsn(Opcodes.INVOKESPECIAL, SPIRE_CONFIG, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false)
            visitInsn(Opcodes.POP)
        })
        assertTrue(result.paths.isEmpty())
        assertTrue(result.incomplete)
    }

    @Test fun makeFilePathSupportsExplicitExtensionButNotOtherFormatsOrTraversal() {
        val result = ModConfigLocator.locate(jar {
            listOf("safe" to "properties", "other" to "json", "../outside" to "properties").forEach { (directory, ext) ->
                visitLdcInsn(directory)
                visitLdcInsn("config")
                visitLdcInsn(ext)
                visitMethodInsn(Opcodes.INVOKESTATIC, SPIRE_CONFIG, "makeFilePath", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false)
                visitInsn(Opcodes.POP)
            }
        })
        assertEquals(setOf("safe/config.properties"), result.paths)
        assertTrue(result.incomplete)
    }

    @Test fun noConfigurationCallsMeansNoAssociationEvenWithPropertiesStrings() {
        val result = ModConfigLocator.locate(jar {
            visitLdcInsn("SomeMod/config.properties")
            visitInsn(Opcodes.POP)
        })
        assertTrue(result.paths.isEmpty())
    }

    @Test fun conflictingBranchValuesAreNotReportedAsCertain() {
        val result = ModConfigLocator.locate(jar {
            val other = org.objectweb.asm.Label()
            val joined = org.objectweb.asm.Label()
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false)
            visitInsn(Opcodes.LCONST_0)
            visitInsn(Opcodes.LCMP)
            visitJumpInsn(Opcodes.IFEQ, other)
            visitLdcInsn("first")
            visitVarInsn(Opcodes.ASTORE, 0)
            visitJumpInsn(Opcodes.GOTO, joined)
            visitLabel(other)
            visitLdcInsn("second")
            visitVarInsn(Opcodes.ASTORE, 0)
            visitLabel(joined)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("settings")
            visitMethodInsn(Opcodes.INVOKESTATIC, SPIRE_CONFIG, "makeFilePath", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
        })
        assertTrue(result.paths.isEmpty())
        assertTrue(result.incomplete)
    }

    @Test fun missingJarDoesNotGuessLocations() {
        val result = ModConfigLocator.locate(File(temporaryFolder.root, "missing.jar"))
        assertTrue(result.paths.isEmpty())
        assertTrue(result.incomplete)
    }

    companion object {
        private const val SPIRE_CONFIG = "com/evacipated/cardcrawl/modthespire/lib/SpireConfig"
    }
}
