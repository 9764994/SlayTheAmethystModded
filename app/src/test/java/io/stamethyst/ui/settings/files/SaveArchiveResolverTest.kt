package io.stamethyst.ui.settings.files

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveArchiveResolverTest {
    private fun withArchive(files: Map<String, String>, action: (java.io.File, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("save-resolver").toFile()
        try {
            val archive = root.resolve("input.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                files.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            action(archive, root)
        } finally { root.deleteRecursively() }
    }

    @Test fun arbitraryWrappersResolveTogether() = withArchive(mapOf(
        "备份/日期/game/preferences/STSPlayer" to "player",
        "备份/日期/game/runs/IRONCLAD/1.run" to "run",
        "备份/日期/game/saves/IRONCLAD.autosave" to "save",
    )) { archive, _ ->
        val candidate = SaveArchiveResolver.inspect(archive).single()
        assertEquals("备份/日期/game", candidate.prefix)
        assertEquals(setOf("preferences/STSPlayer", "runs/IRONCLAD/1.run", "saves/IRONCLAD.autosave"), candidate.entries.map { it.target }.toSet())
    }

    @Test fun flatPreferencesAndRepeatedWrappers() = withArchive(mapOf(
        "outer/preferences/preferences/STSPlayer" to "player",
        "outer/STSUnlocks" to "unlocks",
    )) { archive, _ ->
        assertEquals(setOf("preferences/STSPlayer", "preferences/STSUnlocks"), SaveArchiveResolver.inspect(archive).single().entries.map { it.target }.toSet())
    }

    @Test fun multipleBackupsStaySeparate() = withArchive(mapOf(
        "A/preferences/STSPlayer" to "a", "B/preferences/STSPlayer" to "b",
    )) { archive, _ -> assertEquals(2, SaveArchiveResolver.inspect(archive).size) }

    @Test fun selectionAndChangesPreserveUnselectedFiles() = withArchive(mapOf(
        "preferences/STSPlayer" to "new", "preferences/STSUnlocks" to "unlock",
    )) { archive, root ->
        val target = root.resolve("target").apply { mkdirs() }
        target.resolve("preferences").mkdirs()
        target.resolve("preferences/STSPlayer").writeText("old")
        val candidate = SaveArchiveResolver.inspect(archive).single()
        assertEquals(listOf(SaveArchiveResolver.Change.REPLACED, SaveArchiveResolver.Change.ADDED), SaveArchiveResolver.changes(candidate, target).map { it.change })
        SaveArchiveResolver.extract(archive, candidate, target, setOf("preferences/STSUnlocks"))
        assertEquals("old", target.resolve("preferences/STSPlayer").readText())
        assertEquals("unlock", target.resolve("preferences/STSUnlocks").readText())
    }

    @Test(expected = java.io.IOException::class)
    fun traversalRejected() = withArchive(mapOf("preferences/../STSPlayer" to "x")) { archive, _ -> SaveArchiveResolver.inspect(archive) }

    @Test(expected = java.io.IOException::class)
    fun conflictingAliasesRejected() = withArchive(mapOf("preferences/STSPlayer" to "a", "perference/STSPlayer" to "b")) { archive, _ -> SaveArchiveResolver.inspect(archive) }
}
