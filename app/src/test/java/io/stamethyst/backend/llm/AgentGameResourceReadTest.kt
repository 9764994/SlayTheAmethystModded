package io.stamethyst.backend.llm

import io.stamethyst.backend.mods.ModManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class AgentGameResourceReadTest {
    @get:Rule val temp = TemporaryFolder()

    private fun jar(name: String, vararg entries: Pair<String, ByteArray>): File =
        File(temp.root, name).also { file ->
            JarOutputStream(file.outputStream()).use { output ->
                entries.forEach { (path, bytes) ->
                    output.putNextEntry(JarEntry(path))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }

    private fun field(text: String, name: String) =
        Json.parseToJsonElement(text).jsonObject[name]?.jsonPrimitive?.content

    @Test fun gameListingFiltersAndPagesWithoutChangingTheModDefault() {
        val parent = jar("parent.jar", "parent.txt" to "parent".toByteArray())
        val game = jar("desktop-1.0.jar", "images/b.png" to byteArrayOf(1),
            "images/a.png" to byteArrayOf(2), "Example.class" to byteArrayOf(3))
        val before = game.readBytes()
        val list = AgentModEntriesListTool(parent, gameJar = game)
        assertTrue(list.execute("{}").contains("parent.txt"))
        val first = list.execute("""{"source":"game","prefix":"images/","limit":"1"}""")
        assertEquals("game", field(first, "source"))
        assertEquals("desktop-1.0.jar", field(first, "archive"))
        assertNull(field(first, "mod_id"))
        assertEquals("2", field(first, "total_entries"))
        assertEquals("1", field(first, "next_offset"))
        assertTrue(first.contains("images/a.png"))
        assertFalse(first.contains("images/b.png"))
        val second = list.execute("""{"source":"game","prefix":"images/","offset":"1"}""")
        assertTrue(second.contains("images/b.png"))
        assertFalse(second.contains("parent.txt"))
        assertTrue(list.execute("""{"source":"game","entry_type":"classes"}""").contains("Example.class"))
        assertArrayEquals(before, game.readBytes())
    }

    @Test fun gameImageIsAttachedAndExplicitBase64RemainsText() {
        val parent = jar("parent.jar")
        val game = jar("desktop-1.0.jar", "images/card.png" to Base64.getDecoder().decode(PNG))
        val before = game.readBytes()
        val read = AgentModResourceReadTool(parent, gameJar = game)
        val output = read.executeWithMedia("""{"source":"game","entry":"images/card.png"}""")
        assertEquals("game", field(output.text, "source"))
        assertEquals("image", field(output.text, "type"))
        assertEquals("desktop-1.0.jar", field(output.text, "archive"))
        assertNull(field(output.text, "mod_id"))
        assertEquals("image/png", output.attachments.single().mime)
        assertEquals(PNG, output.attachments.single().data)
        val base64 = read.executeWithMedia("""{"source":"game","entry":"images/card.png","encoding":"base64"}""")
        assertTrue(base64.attachments.isEmpty())
        assertEquals(PNG, field(base64.text, "content"))
        assertArrayEquals(before, game.readBytes())
    }

    @Test fun textRangesMissingEntriesAndModRoutingRemainIndependent() {
        val parent = jar("parent.jar", "same.txt" to "parent".toByteArray(),
            "parent-only.txt" to "private to parent".toByteArray())
        val other = jar("other.jar", "same.txt" to "other".toByteArray())
        val game = jar("desktop-1.0.jar", "same.txt" to "game-text".toByteArray())
        val targets = AgentModReadTargets("parent", parent) {
            listOf(ModManager.InstalledMod("other", "Other", "Other", "1", "", emptyList(),
                other, false, true, false, null, null))
        }
        val read = AgentModResourceReadTool(parent, targets, game)
        assertEquals("parent", field(read.execute("""{"entry":"same.txt"}"""), "content"))
        assertEquals("other", field(read.execute("""{"source":"mod","mod_id":"other","entry":"same.txt"}"""), "content"))
        val range = read.execute("""{"source":"game","entry":"same.txt","offset":"2","limit":"3"}""")
        assertEquals("me-", field(range, "content"))
        assertEquals("9", field(range, "total_bytes"))
        assertEquals("5", field(range, "next_offset"))
        assertEquals("resource_not_found", field(read.execute("""{"source":"game","entry":"missing"}"""), "error"))
        assertEquals("resource_not_found", field(read.execute("""{"source":"game","entry":"parent-only.txt"}"""), "error"))
        assertEquals("resource_not_found", field(read.execute("""{"source":"game","entry":"../same.txt"}"""), "error"))
    }

    @Test fun bothToolsRejectInvalidSourcesConflictsAndMissingGameJars() {
        val parent = jar("parent.jar")
        val game = jar("desktop-1.0.jar")
        for (tool in listOf(AgentModEntriesListTool(parent, gameJar = game),
            AgentModResourceReadTool(parent, gameJar = game))) {
            assertEquals(AgentToolSafety.READ_ONLY, tool.safety)
            assertEquals(AgentToolConcurrency.PARALLEL, tool.concurrency)
            assertTrue(tool.specification.parameters().properties().containsKey("source"))
            assertFalse(tool.specification.parameters().required().orEmpty().contains("source"))
            assertEquals("invalid_source", field(tool.execute("""{"source":"${game.absolutePath}","entry":"x"}"""), "error"))
            assertEquals("mod_id_not_allowed_for_game_source", field(tool.execute("""{"source":"game","mod_id":"other","entry":"x"}"""), "error"))
        }
        for (missing in listOf(null, File(temp.root, "missing.jar"), temp.root)) {
            assertEquals("game_jar_not_found", field(AgentModEntriesListTool(parent, gameJar = missing)
                .execute("""{"source":"game"}"""), "error"))
            assertEquals("game_jar_not_found", field(AgentModResourceReadTool(parent, gameJar = missing)
                .execute("""{"source":"game","entry":"x"}"""), "error"))
        }
    }

    private companion object {
        const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
