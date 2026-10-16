package io.stamethyst.backend.mods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.Properties

class ModConfigRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder(File("../agent-tmp").apply { mkdirs() })

    private fun root(): File = temporaryFolder.newFolder()

    private fun write(root: File, path: String, text: String): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(text, Charsets.ISO_8859_1)
    }

    @Test
    fun discoverRanksIdentityMatchesAndKeepsCustomDirectoriesSelectable() {
        val root = root()
        write(root, "CustomFolder/options.properties", "a=true")
        write(root, "My Mod/config.properties", "a=false")
        write(root, "other/MY-MOD.properties", "a=1")
        write(root, "ModTheSpire.properties", "a=2")
        write(root, "My Mod/config.properties.bak", "a=old")
        write(root, "My Mod/settings.json", "{}")

        val files = ModConfigRepository(root).discover(listOf("my_mod", ""))

        assertEquals(4, files.size)
        assertTrue(files.take(2).all { it.recommended })
        assertFalse(files.drop(2).any { it.recommended })
        assertTrue(files.any { it.relativePath == "CustomFolder/options.properties" })
    }

    @Test
    fun missingDirectoryDoesNotGetCreated() {
        val missing = File(root(), "missing")
        assertTrue(ModConfigRepository(missing).discover(listOf("mod")).isEmpty())
        assertFalse(missing.exists())
    }

    @Test
    fun loadUsesJavaPropertiesEscapesContinuationsAndTypes() {
        val root = root()
        write(root, "mod/settings.properties", """
            # settings
            enabled=true
            count=-12
            speed=1.25e2
            title=\u4E2D\u6587
            escaped\ key\:=hello\nworld
            continued=first\
                second
            blank=
        """.trimIndent())
        val entries = ModConfigRepository(root).load("mod/settings.properties").entries.associateBy { it.key }

        assertEquals("中文", entries.getValue("title").value)
        assertEquals("hello\nworld", entries.getValue("escaped key:").value)
        assertEquals("firstsecond", entries.getValue("continued").value)
        assertEquals(ModConfigValueType.BOOLEAN, entries.getValue("enabled").type)
        assertEquals(ModConfigValueType.INTEGER, entries.getValue("count").type)
        assertEquals(ModConfigValueType.DECIMAL, entries.getValue("speed").type)
        assertEquals(ModConfigValueType.TEXT, entries.getValue("blank").type)
    }

    @Test
    fun savePreservesCommentsUntouchedLinesAndBacksUpExactOriginal() {
        val root = root()
        val original = "# Keep this comment\r\n! Also keep this\\\r\nflag : false\r\ncount=01\r\ntext=old"
        val file = write(root, "mod/config.properties", original)
        val repository = ModConfigRepository(root)
        val document = repository.load("mod/config.properties")
        val values = document.entries.associate { it.key to it.value } + ("flag" to "true") +
            ("text" to " 中文\n=:#\\end ")

        val saved = repository.save(document, values)

        assertEquals(original, File(root, "mod/config.properties.bak").readText(Charsets.ISO_8859_1))
        assertTrue(file.readText(Charsets.ISO_8859_1).startsWith("# Keep this comment\r\n! Also keep this\\\r\nflag=true\r\ncount=01\r\n"))
        assertFalse(file.readBytes().last() == '\n'.code.toByte())
        assertEquals(values, saved.entries.associate { it.key to it.value })
        assertEquals(values, repository.load("mod/config.properties").entries.associate { it.key to it.value })
    }

    @Test
    fun saveUpdatesLastDuplicateKeyAndContinuedValuesWithoutChangingOtherRecords() {
        val root = root()
        val file = write(root, "mod/config.properties", "duplicate=first\n# note\nduplicate=last\nlong=one\\\n  two\nuntouched=a\\\n b\n")
        val repository = ModConfigRepository(root)
        val document = repository.load("mod/config.properties")
        repository.save(document, document.entries.associate { it.key to it.value } +
            ("duplicate" to "new") + ("long" to "replacement"))

        assertEquals("duplicate=first\n# note\nduplicate=new\nlong=replacement\nuntouched=a\\\n b\n", file.readText())
    }

    @Test
    fun saveRoundTripsSpecialKeysAndAllStringCharacters() {
        val root = root()
        val file = File(root, "config.properties")
        val keys = listOf("#hash", "!bang", " key =:\\", "中文", "multi\nline")
        val initial = Properties().apply { keys.forEach { setProperty(it, "old") } }
        file.outputStream().use { initial.store(it, null) }
        val repository = ModConfigRepository(root)
        val document = repository.load("config.properties")
        val values = keys.associateWith { " \t\n\r\u000c\u0000中文😀\\=:# ! " }
        repository.save(document, values)

        assertEquals(values, repository.load("config.properties").entries.associate { it.key to it.value })
    }

    @Test
    fun unchangedSaveDoesNotRewriteOrCreateBackup() {
        val root = root()
        val file = write(root, "config.properties", "# original\na=1\n")
        val repository = ModConfigRepository(root)
        val document = repository.load("config.properties")
        repository.save(document, document.entries.associate { it.key to it.value })

        assertArrayEquals(document.originalBytes, file.readBytes())
        assertFalse(File(root, "config.properties.bak").exists())
    }

    @Test
    fun externallyModifiedOrDeletedFileCannotBeOverwritten() {
        val root = root()
        val file = write(root, "config.properties", "a=1\n")
        val repository = ModConfigRepository(root)
        val document = repository.load("config.properties")
        file.writeText("a=3\n")

        assertThrows(ModConfigConflictException::class.java) { repository.save(document, mapOf("a" to "2")) }
        assertEquals("a=3\n", file.readText())
        assertFalse(File(root, "config.properties.bak").exists())
        file.delete()
        assertThrows(ModConfigConflictException::class.java) { repository.save(document, mapOf("a" to "2")) }
        assertFalse(file.exists())
    }

    @Test
    fun invalidKeysOrTypesCannotChangeTheFile() {
        val root = root()
        val file = write(root, "config.properties", "a=1\nflag=true\nspeed=0.5\n")
        val original = file.readBytes()
        val repository = ModConfigRepository(root)
        val document = repository.load("config.properties")
        val values = document.entries.associate { it.key to it.value }

        listOf(values - "a", values + ("new" to "x"), values + ("a" to "1.5"),
            values + ("flag" to "yes"), values + ("speed" to "NaN")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { repository.save(document, invalid) }
        }
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun rejectsTraversalAbsolutePathsAndSymlinks() {
        val root = root()
        val outside = write(root(), "external.properties", "a=1")
        val repository = ModConfigRepository(root)
        assertThrows(IllegalArgumentException::class.java) { repository.load("../external.properties") }
        assertThrows(IllegalArgumentException::class.java) { repository.load(outside.absolutePath) }
        val link = Files.createSymbolicLink(File(root, "linked.properties").toPath(), outside.toPath())
        val loop = Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        try {
            assertTrue(repository.discover(listOf("linked")).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { repository.load("linked.properties") }
        } finally {
            Files.deleteIfExists(loop)
            Files.deleteIfExists(link)
        }
    }

    @Test
    fun symlinkBackupCannotOverwriteExternalFile() {
        val root = root()
        val file = write(root, "config.properties", "a=1")
        val outside = write(root(), "external.properties", "outside")
        Files.createSymbolicLink(File(root, "config.properties.bak").toPath(), outside.toPath())
        val repository = ModConfigRepository(root)
        val document = repository.load("config.properties")

        assertThrows(IllegalArgumentException::class.java) { repository.save(document, mapOf("a" to "2")) }
        assertEquals("a=1", file.readText())
        assertEquals("outside", outside.readText())
    }

    @Test
    fun malformedUnicodeAndOversizeFilesAreNotEditable() {
        val root = root()
        write(root, "invalid.properties", "value=\\uGGGG")
        write(root, "large.properties", "a=" + "x".repeat(1024 * 1024))
        val repository = ModConfigRepository(root)
        assertThrows(IllegalArgumentException::class.java) { repository.load("invalid.properties") }
        assertThrows(java.io.IOException::class.java) { repository.load("large.properties") }
    }

    @Test
    fun backupContainsPreviousVersionOnEachSave() {
        val root = root()
        val file = write(root, "config.properties", "a=1")
        val repository = ModConfigRepository(root)
        val second = repository.save(repository.load("config.properties"), mapOf("a" to "2"))
        val previous = file.readBytes()
        repository.save(second, mapOf("a" to "3"))
        assertArrayEquals(previous, File(root, "config.properties.bak").readBytes())
        assertEquals("3", repository.load("config.properties").entries.single().value)
    }
}
