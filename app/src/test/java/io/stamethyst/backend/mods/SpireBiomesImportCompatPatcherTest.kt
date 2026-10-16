package io.stamethyst.backend.mods

import io.stamethyst.backend.mods.importing.patches.mods.spirebiomes.SpireBiomesImportCompatPatcher
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpireBiomesImportCompatPatcherTest {
    @Test
    fun patchSource_hoistsOnlyTheInvariantCalculation() {
        val original = originalSource()
        val patched = SpireBiomesImportCompatPatcher.patchSource(original)
        assertNotNull(patched)
        val loopStart = patched!!.indexOf("for (int i = 0; i < u_size; i++)")
        val loopEnd = patched.indexOf("vec3 BG", loopStart)
        assertTrue(patched.indexOf("float baseFog") < loopStart)
        assertFalse(patched.substring(loopStart, loopEnd).contains("fractal_brownian_motion"))
        assertTrue(patched.substring(loopStart, loopEnd).contains("float fog = baseFog * (1.0 - fogFactor);"))

        // Reverse only the intended edits: everything else, including FBM iterations, uniforms,
        // cursor ages, early exit, min and final blending, must remain byte-for-byte unchanged.
        val hoistedStart = patched.indexOf("// ${SpireBiomesImportCompatPatcher.PATCH_MARKER}")
        val initializer = patched.indexOf("float finalFog = 1.0;", hoistedStart)
        val restored = patched.removeRange(hoistedStart, initializer).replace(
            "float fog = baseFog * (1.0 - fogFactor);",
            "vec2 pos = vec2(st * u_zoom);\n" +
                "        vec2 motion = vec2(fractal_brownian_motion(pos + vec2(u_time * -0.5, u_time * -0.3)));\n" +
                "        float fog = fractal_brownian_motion(pos + motion) * u_intensity * (1.0 - fogFactor);"
        )
        assertEquals(original, restored)
        assertNull(SpireBiomesImportCompatPatcher.patchSource(patched))
    }

    @Test
    fun patchSource_acceptsLineEndingAndIndentationChanges() {
        val original = originalSource().replace("\n", "\r\n").replace("        ", "\t")
        val patched = SpireBiomesImportCompatPatcher.patchSource(original)
        assertNotNull(patched)
        assertFalse(patched!!.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun patchSource_rejectsUnknownOrModifiedShaders() {
        val original = originalSource()
        listOf(
            original.replace("u_time * -0.5", "u_time * -0.6"),
            original.replace("pos + motion", "pos + motion + vec2(float(i))"),
            original.replace("finalFog < 0.06", "finalFog < 0.1"),
            original.replace("i <2", "i <4"),
            original.replace("\n", " "), // Do not erase // comment boundaries during identification.
            original + "\n// Modified shader\n",
            "void main() {}"
        ).forEach { assertNull(SpireBiomesImportCompatPatcher.patchSource(it)) }
    }

    @Test
    fun patchInPlace_preservesOtherEntriesAndIsIdempotent() = withTestJar { jar ->
        val untouched = mapOf(
            "ModTheSpire.json" to "{\"modid\":\"anniv6\"}".toByteArray(),
            "anniv6Resources/shaders/fog/vertex.vs" to "void main() {}".toByteArray(),
            "another/fog/fragment.fs" to originalSource().toByteArray(),
            "some/Class.class" to byteArrayOf(0, -1, 15, 127)
        )
        createJar(jar, untouched + (targetEntry to originalSource().toByteArray()))
        val result = SpireBiomesImportCompatPatcher.patchInPlace(jar)
        assertEquals(1, result.patchedShaderEntries)
        assertTrue(result.hasAnyPatch)
        assertEquals(0, result.unsupportedShaderEntries)
        ZipFile(jar).use { zip ->
            untouched.forEach { (name, bytes) ->
                assertTrue(bytes.contentEquals(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }))
                assertEquals("preserved comment", zip.getEntry(name).comment)
            }
            assertTrue(zip.getEntry("empty/").isDirectory)
            val patched = zip.getInputStream(zip.getEntry(targetEntry)).bufferedReader().use { it.readText() }
            assertEquals(SpireBiomesImportCompatPatcher.patchSource(originalSource()), patched)
        }
        val bytesAfterFirstPatch = jar.readBytes()
        val second = SpireBiomesImportCompatPatcher.patchInPlace(jar)
        assertEquals(0, second.patchedShaderEntries)
        assertEquals(1, second.alreadyPatchedShaderEntries)
        assertFalse(second.hasAnyPatch)
        assertTrue(bytesAfterFirstPatch.contentEquals(jar.readBytes()))
        assertFalse(File(jar.absolutePath + ".spirebiomespatch.tmp").exists())
        assertFalse(File(jar.absolutePath + ".bak").exists())
    }

    @Test
    fun patchInPlace_leavesUnknownSourceUnchanged() = withTestJar { jar ->
        createJar(jar, mapOf(targetEntry to originalSource().replace("i <2", "i <3").toByteArray()))
        val before = jar.readBytes()
        val result = SpireBiomesImportCompatPatcher.patchInPlace(jar)
        assertEquals(1, result.unsupportedShaderEntries)
        assertFalse(result.hasAnyPatch)
        assertTrue(before.contentEquals(jar.readBytes()))
    }

    @Test
    fun patchInPlace_doesNotMatchMissingOrSuffixOnlyPaths() = withTestJar { jar ->
        createJar(jar, mapOf("other/$targetEntry" to originalSource().toByteArray()))
        val before = jar.readBytes()
        val result = SpireBiomesImportCompatPatcher.patchInPlace(jar)
        assertEquals(0, result.patchedShaderEntries)
        assertEquals(0, result.unsupportedShaderEntries)
        assertTrue(before.contentEquals(jar.readBytes()))
    }

    private val targetEntry = SpireBiomesImportCompatPatcher.FOG_FRAGMENT_ENTRY

    private fun originalSource(): String = javaClass.getResourceAsStream("/spirebiomes/fog-1.6.4.fs")!!
        .bufferedReader().use { it.readText() }

    private fun withTestJar(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("spirebiomes-import-patch").toFile()
        try {
            block(File(directory, "SpireBiomes.jar"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createJar(jar: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("empty/"))
            out.closeEntry()
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name).apply { comment = "preserved comment" })
                out.write(bytes)
                out.closeEntry()
            }
        }
    }
}
