package io.stamethyst.backend.mods

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.config.RuntimePaths
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentModInspectionManagerTest {
    @Test
    fun createInspection_preparesOnDemandSourceCacheWithoutExtractingJar() {
        val root = Files.createTempDirectory("agent-mod-inspection").toFile()
        val context = testContext(root)
        val source = File(root, "source.jar")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ModTheSpire.json"))
            zip.write("{\"modid\":\"ShoujoKageki\"}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("com/example/Parent.class"))
            zip.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            zip.closeEntry()
        }

        val inspection = AgentModInspectionManager.createInspection(
            context = context,
            parentModId = "shoujokageki",
            sourceJar = source,
        )

        assertEquals("ShoujoKageki", inspection.parentModId)
        assertEquals(RuntimePaths.agentModWorkspaceRoot(context, "shoujokageki"), inspection.root)
        assertEquals(RuntimePaths.agentModSourceRoot(context, "shoujokageki"), inspection.sourceRoot)
        assertTrue(inspection.sourceRoot.isDirectory)
        assertTrue("Inspection must not eagerly extract JAR entries", inspection.sourceRoot.listFiles()!!.isEmpty())
        assertFalse(inspection.root.resolve("patch_source").exists())
        val metadata = JSONObject(inspection.root.resolve("source-metadata.json").readText())
        assertEquals("ShoujoKageki", metadata.getString("parent_mod_id"))
        assertEquals(inspection.inspectionId, metadata.getString("inspection_id"))
        assertEquals(source.canonicalPath, metadata.getString("source_path"))
        assertEquals(AgentModJarReader.sha256(source), inspection.sourceSha256)
        assertEquals(inspection.sourceSha256, metadata.getString("source_sha256"))
        assertEquals("source", metadata.getString("source_root"))
        assertEquals("on_demand", metadata.getString("source_mode"))
        assertFalse(metadata.getBoolean("source_extracted"))
        // Decompilation state is only recorded once a class has actually been materialized.
        assertFalse(metadata.has("source_decompiled"))
    }

    private fun testContext(root: File): Context {
        val filesDir = File(root, "files").apply { mkdirs() }
        val externalFilesDir = File(root, "external").apply { mkdirs() }
        return object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = filesDir
            override fun getExternalFilesDir(type: String?): File = externalFilesDir
        }
    }
}
