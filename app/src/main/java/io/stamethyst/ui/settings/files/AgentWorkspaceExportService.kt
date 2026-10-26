package io.stamethyst.ui.settings.files

import android.app.Activity
import android.net.Uri
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports the AI agent workspace (`sts/agent_workspace`) as a single zip archive.
 *
 * The workspace contains conversation SQLite databases, which Android creates with mode 0600
 * (`SQLITE_DEFAULT_FILE_PERMISSIONS`). Writing the archive through a SAF destination gives the
 * user a normal, readable copy without touching the live files.
 */
internal object AgentWorkspaceExportService {
    private const val LOG_TAG = "AgentWorkspaceExport"
    private const val ARCHIVE_ROOT = "agent_workspace"
    private const val READ_BUFFER_BYTES = 32 * 1024

    fun buildExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-agent-workspace-export-${formatter.format(Date())}.zip"
    }

    /**
     * Zips the live workspace into the user-picked document. Returns the exported file count.
     */
    @Throws(IOException::class)
    fun exportWorkspace(
        host: Activity,
        uri: Uri,
        onProgress: ((Int) -> Unit)? = null,
    ): Int {
        host.contentResolver.openOutputStream(uri).use { output ->
            if (output == null) {
                throw IOException("Unable to open destination file")
            }
            return zipWorkspace(RuntimePaths.agentWorkspaceRoot(host), output, onProgress)
        }
    }

    /**
     * Zips every regular file under [root], including hidden directories such as `.conversations`.
     * Returns the number of exported files, or 0 when the workspace is empty.
     */
    @Throws(IOException::class)
    internal fun zipWorkspace(
        root: File,
        output: OutputStream,
        onProgress: ((Int) -> Unit)? = null,
    ): Int {
        val files = collectFiles(root)
        ZipOutputStream(output).use { zipOutput ->
            if (files.isEmpty()) {
                writeEmptyWorkspaceNote(zipOutput, root)
                onProgress?.invoke(100)
                return 0
            }

            val totalBytes = files.sumOf { it.length() }.coerceAtLeast(1L)
            var writtenBytes = 0L
            var exportedCount = 0
            var lastReportedPercent = -1
            onProgress?.invoke(0)

            files.forEach { file ->
                // WAL side files can be removed by SQLite between listing and reading.
                val input = try {
                    FileInputStream(file)
                } catch (error: IOException) {
                    android.util.Log.w(
                        LOG_TAG,
                        "Skipping unreadable agent workspace file: ${file.absolutePath}",
                        error,
                    )
                    return@forEach
                }
                input.use { stream ->
                    val entryName = "$ARCHIVE_ROOT/${file.relativeTo(root).invariantSeparatorsPath}"
                    val entry = ZipEntry(entryName)
                    if (file.lastModified() > 0) {
                        entry.time = file.lastModified()
                    }
                    zipOutput.putNextEntry(entry)
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) {
                            break
                        }
                        zipOutput.write(buffer, 0, read)
                        writtenBytes += read
                        // Hold 100% until the central directory is written.
                        val percent = ((writtenBytes * 100) / totalBytes).toInt().coerceIn(0, 99)
                        if (percent > lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress?.invoke(percent)
                        }
                    }
                    zipOutput.closeEntry()
                }
                exportedCount += 1
            }
            onProgress?.invoke(100)
            return exportedCount
        }
    }

    private fun writeEmptyWorkspaceNote(zipOutput: ZipOutputStream, root: File) {
        zipOutput.putNextEntry(ZipEntry("$ARCHIVE_ROOT/README.txt"))
        val message = "No agent workspace files found.\n" +
            "Expected directory: ${root.absolutePath}\n"
        zipOutput.write(message.toByteArray(Charsets.UTF_8))
        zipOutput.closeEntry()
    }

    /**
     * Symlinked directories are not followed so a link loop cannot hang the export, and symlinked
     * files are skipped because their content already exists elsewhere in the workspace.
     */
    private fun collectFiles(root: File): List<File> {
        if (!root.isDirectory) {
            return emptyList()
        }
        return root.walkTopDown()
            .onEnter { directory -> !Files.isSymbolicLink(directory.toPath()) }
            .filter { file -> Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .sortedBy { file -> file.relativeTo(root).invariantSeparatorsPath }
            .toList()
    }
}
