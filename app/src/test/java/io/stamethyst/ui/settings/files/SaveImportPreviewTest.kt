package io.stamethyst.ui.settings.files

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveImportPreviewTest {
    @Test fun previewsActualFieldsAndCountsPlayableRunsOnly() {
        val root = Files.createTempDirectory("save-preview").toFile()
        try {
            val archive = root.resolve("input.zip")
            val files = mapOf(
                "preferences/STSDataVagabond" to """{"ASCENSION_LEVEL":"20","WIN_COUNT":"10"}""",
                "runs/IRONCLAD/1.run" to "{}",
                "saves/IRONCLAD.autosave" to "save",
                "saves/IRONCLAD.autosave.bak" to "backup",
            )
            ZipOutputStream(archive.outputStream()).use { zip ->
                files.forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
                }
            }
            val target = root.resolve("target")
            target.resolve("preferences").mkdirs()
            target.resolve("preferences/STSDataVagabond").writeText("""{"ASCENSION_LEVEL":"8"}""")
            val summary = SaveImportPreview.build(SaveArchiveResolver.inspect(archive).single(), target, archive)
            val character = summary.items.first { it.category == SaveImportPreview.Category.CHARACTER_PROGRESS }
            assertEquals("铁甲战士", character.character)
            assertEquals(SaveImportPreview.FieldChange("ASCENSION_LEVEL", "8", "20"), character.fields.first())
            assertEquals(1, summary.addedHistory)
            assertEquals(1, summary.addedActiveRuns)
            assertTrue(character.contentRecognized)
            target.resolve("preferences/STSDataVagabond").writeText("""{"ASCENSION_LEVEL":"9"}""")
            val changed = SaveImportPreview.build(SaveArchiveResolver.inspect(archive).single(), target, archive)
            assertNotEquals(summary, changed)
        } finally { root.deleteRecursively() }
    }
}
