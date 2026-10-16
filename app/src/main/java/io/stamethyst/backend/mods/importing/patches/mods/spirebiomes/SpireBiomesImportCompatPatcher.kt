package io.stamethyst.backend.mods.importing.patches.mods.spirebiomes

import io.stamethyst.backend.mods.JarFileIoUtils
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class SpireBiomesFogPatchResult(
    val patchedShaderEntries: Int = 0,
    val alreadyPatchedShaderEntries: Int = 0,
    val unsupportedShaderEntries: Int = 0,
) {
    val hasAnyPatch: Boolean
        get() = patchedShaderEntries > 0
}

/** Hoists loop-invariant FBM only for the fog source verified in Spire Biomes 1.6.4. */
internal object SpireBiomesImportCompatPatcher {
    internal const val FOG_FRAGMENT_ENTRY = "anniv6Resources/shaders/fog/fragment.fs"
    internal const val PATCH_MARKER = "Amethyst Spire Biomes fog FBM hoist v1"
    private const val ORIGINAL_FINGERPRINT =
        "4b4767e4eae3cb7d1ad1b0ff13681390e5673e66dc6651e9c69dc46dd8e578ba"
    private val LOOP_LOCAL_FBM = Regex(
        "vec2\\s+pos\\s*=\\s*vec2\\(st\\s*\\*\\s*u_zoom\\);\\s*" +
            "vec2\\s+motion\\s*=\\s*vec2\\(fractal_brownian_motion\\(pos\\s*\\+\\s*" +
            "vec2\\(u_time\\s*\\*\\s*-0\\.5,\\s*u_time\\s*\\*\\s*-0\\.3\\)\\)\\);\\s*" +
            "float\\s+fog\\s*=\\s*fractal_brownian_motion\\(pos\\s*\\+\\s*motion\\)\\s*" +
            "\\*\\s*u_intensity\\s*\\*\\s*\\(1\\.0\\s*-\\s*fogFactor\\);"
    )
    private val FINAL_FOG_INITIALIZER = Regex("float\\s+finalFog\\s*=\\s*1\\.0;")

    @Throws(IOException::class)
    fun patchInPlace(modJar: File): SpireBiomesFogPatchResult {
        if (!modJar.isFile) throw IOException("Mod jar not found: ${modJar.absolutePath}")
        val patchedSource = ZipFile(modJar).use { zip ->
            // Match the actual resource name, not a suffix in some unrelated resource tree.
            val entry = zip.getEntry(FOG_FRAGMENT_ENTRY)
                ?: return SpireBiomesFogPatchResult()
            if (entry.isDirectory) return SpireBiomesFogPatchResult()
            val source = JarFileIoUtils.readEntry(zip, entry)
            if (source.contains(PATCH_MARKER)) {
                return SpireBiomesFogPatchResult(alreadyPatchedShaderEntries = 1)
            }
            patchSource(source)
                ?: return SpireBiomesFogPatchResult(unsupportedShaderEntries = 1)
        }
        rewriteJar(modJar, patchedSource)
        return SpireBiomesFogPatchResult(patchedShaderEntries = 1)
    }

    internal fun patchSource(source: String): String? {
        if (source.contains(PATCH_MARKER) || fingerprint(source) != ORIGINAL_FINGERPRINT) {
            return null
        }
        val calculation = LOOP_LOCAL_FBM.findAll(source).toList().singleOrNull() ?: return null
        val initializer = FINAL_FOG_INITIALIZER.findAll(source).toList().singleOrNull() ?: return null
        if (calculation.range.first <= initializer.range.last) return null
        val lineSeparator = if (source.contains("\r\n")) "\r\n" else "\n"
        val hoisted = listOf(
            "// $PATCH_MARKER",
            "vec2 pos = vec2(st * u_zoom);",
            "vec2 motion = vec2(fractal_brownian_motion(pos + vec2(u_time * -0.5, u_time * -0.3)));",
            "float baseFog = fractal_brownian_motion(pos + motion) * u_intensity;",
            "float finalFog = 1.0;"
        ).joinToString(lineSeparator + "    ")
        // Replace the later range first, so offsets into the original source remain valid.
        return source.replaceRange(calculation.range, "float fog = baseFog * (1.0 - fogFactor);")
            .replaceRange(initializer.range, hoisted)
    }

    private fun fingerprint(source: String): String {
        // Preserve line boundaries: collapsing all whitespace can make a // comment swallow code
        // while still yielding the same fingerprint.
        val normalized = source.lineSequence().joinToString("\n") { it.trim() }.trim()
        return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun rewriteJar(modJar: File, patchedSource: String) {
        val tempJar = File(modJar.absolutePath + ".spirebiomespatch.tmp")
        try {
            ZipFile(modJar).use { zip ->
                ZipOutputStream(tempJar.outputStream()).use { out ->
                    val seenNames = HashSet<String>()
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!seenNames.add(entry.name)) continue
                        val replacement = ZipEntry(entry.name)
                        if (entry.time > 0) replacement.time = entry.time
                        replacement.comment = entry.comment
                        out.putNextEntry(replacement)
                        if (entry.name == FOG_FRAGMENT_ENTRY) {
                            out.write(patchedSource.toByteArray(Charsets.UTF_8))
                        } else if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { JarFileIoUtils.copyStream(it, out) }
                        }
                        out.closeEntry()
                    }
                }
            }
            JarFileIoUtils.moveFileReplacing(tempJar, modJar)
        } finally {
            if (tempJar.exists()) tempJar.delete()
        }
    }
}
