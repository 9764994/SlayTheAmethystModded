package io.stamethyst.ui.settings.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipInputStream

class AgentWorkspaceExportServiceTest {
    @Test
    fun zipWorkspace_includesHiddenConversationDirectories() {
        val root = Files.createTempDirectory("agent-workspace-export").toFile()
        try {
            val conversations = root.resolve(".conversations/demo")
            assertTrue(conversations.mkdirs())
            conversations.resolve("chat.db").writeBytes(byteArrayOf(1, 2, 3, 4))
            root.resolve("demo").mkdirs()
            root.resolve("demo/mod-metadata.json")
                .writeText("""{"name":"demo"}""", StandardCharsets.UTF_8)

            val archive = ByteArrayOutputStream()
            val exportedCount = AgentWorkspaceExportService.zipWorkspace(root, archive)

            assertEquals(2, exportedCount)
            assertEquals(
                listOf(
                    "agent_workspace/.conversations/demo/chat.db",
                    "agent_workspace/demo/mod-metadata.json",
                ),
                zipEntryNames(archive.toByteArray()),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zipWorkspace_writesNoteWhenWorkspaceIsMissing() {
        val root = Files.createTempDirectory("agent-workspace-export-empty").toFile()
        val missingRoot = root.resolve("missing")
        try {
            val archive = ByteArrayOutputStream()
            val exportedCount = AgentWorkspaceExportService.zipWorkspace(missingRoot, archive)

            assertEquals(0, exportedCount)
            val names = zipEntryNames(archive.toByteArray())
            assertEquals(listOf("agent_workspace/README.txt"), names)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zipWorkspace_reportsMonotonicProgressEndingAtHundred() {
        val root = Files.createTempDirectory("agent-workspace-export-progress").toFile()
        try {
            root.resolve("source").mkdirs()
            root.resolve("source/Class.java")
                .writeText("class Class {}".repeat(4096), StandardCharsets.UTF_8)
            val progress = mutableListOf<Int>()

            AgentWorkspaceExportService.zipWorkspace(root, ByteArrayOutputStream()) { percent ->
                progress += percent
            }

            assertEquals(100, progress.last())
            assertEquals(progress.sorted(), progress)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zipEntryNames(archive: ByteArray): List<String> {
        return ZipInputStream(archive.inputStream()).use { zip ->
            buildList {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    add(entry.name)
                    zip.closeEntry()
                }
            }
        }
    }
}
