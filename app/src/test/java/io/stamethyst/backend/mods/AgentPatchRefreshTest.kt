package io.stamethyst.backend.mods

import android.app.Application
import android.content.ContextWrapper
import io.stamethyst.config.RuntimePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentPatchRefreshTest {
    @get:Rule
    val temporary = TemporaryFolder(File("agent-tmp").absoluteFile.apply { mkdirs() })

    @Test
    fun bulkListingMatchesSingleParentListingAndRefreshesEnablement() {
        val context = context()
        val first = patch(RuntimePaths.agentModsForModRoot(context, "parent"), "one", "parent")
        patch(RuntimePaths.agentModsForModRoot(context, "other"), "two", "other")
        patch(RuntimePaths.agentModsForModRoot(context, "unselected"), "three", "unselected")
        patch(RuntimePaths.agentModsForModRoot(context, "parent"), "wrong-parent", "other")

        val bulk = AgentPatchModManager.listPackagedForParents(context, listOf("Parent", "other", "parent", "../bad"))
        assertEquals(setOf("one", "two"), bulk.map { it.patchId }.toSet())
        assertEquals(
            AgentPatchModManager.listPackaged(context, "Parent"),
            bulk.filter { it.parentModId == "parent" },
        )
        assertTrue(bulk.none { it.enabled })
        RuntimePaths.enabledAgentPatchModsConfig(context).apply {
            parentFile?.mkdirs()
            writeText("ai.one\n")
        }
        val updated = AgentPatchModManager.listPackagedForParents(context, listOf("parent", "other"))
        assertTrue(updated.single { it.patchId == "one" }.enabled)
        assertFalse(updated.single { it.patchId == "two" }.enabled)
        assertTrue(first.delete())
        assertEquals(listOf("two"), AgentPatchModManager.listPackagedForParents(context, listOf("parent", "other")).map { it.patchId })
    }

    @Test
    fun patchQueriesAndLaunchIgnore147JarsInOrdinaryLibrary() {
        val context = context()
        val library = RuntimePaths.optionalModsLibraryDir(context).apply { mkdirs() }
        // Even a valid descriptor in the ordinary library is not an AI-patch discovery source.
        val misplaced = (1..147).map { patch(library, "ordinary-$it", "parent") }
        val originalBytes = misplaced.associateWith { it.readBytes().toList() }
        val enabledConfig = RuntimePaths.enabledAgentPatchModsConfig(context).apply {
            parentFile?.mkdirs()
            writeText(misplaced.joinToString("\n") { "ai.${it.nameWithoutExtension}" } + "\nai.current\n")
        }
        val originalConfig = enabledConfig.readText()
        repeat(2) {
            assertTrue(AgentPatchModManager.listPackaged(context, "parent").isEmpty())
            assertTrue(AgentPatchModManager.listPackagedForParents(context, listOf("parent")).isEmpty())
            assertTrue(AgentPatchModManager.listEnabledForLaunch(context, setOf("parent")).isEmpty())
            assertFalse(RuntimePaths.agentModsRoot(context).exists())
        }

        val current = patch(RuntimePaths.agentModsForModRoot(context, "parent"), "current", "parent")
        repeat(5) {
            assertEquals(listOf(current), AgentPatchModManager.listPackaged(context, "parent").map { it.jarFile })
            assertEquals(listOf(current), AgentPatchModManager.listPackagedForParents(context, listOf("parent")).map { it.jarFile })
            val launch = AgentPatchModManager.listEnabledForLaunch(context, setOf("parent"))
            assertEquals(listOf(current), launch.map { it.jarFile })
            assertTrue(launch.single().enabled)
        }
        assertEquals(147, library.listFiles()!!.size)
        originalBytes.forEach { (file, bytes) -> assertEquals(bytes, file.readBytes().toList()) }
        assertEquals(originalConfig, enabledConfig.readText())
    }

    private fun context(): ContextWrapper {
        val root = temporary.newFolder()
        val files = File(root, "files").apply { mkdirs() }
        val external = File(root, "external").apply { mkdirs() }
        return object : ContextWrapper(Application()) {
            override fun getFilesDir() = files
            override fun getExternalFilesDir(type: String?) = external
        }
    }

    private fun patch(directory: File, id: String, parent: String): File {
        directory.mkdirs()
        val file = File(directory, "$id.jar")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ModTheSpire.json"))
            zip.write("""{"modid":"ai.$id"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("agent-patch.json"))
            zip.write("""{"parent_mod_id":"$parent","patch_id":"$id","patch_mod_id":"ai.$id"}""".toByteArray())
            zip.closeEntry()
        }
        return file
    }
}
