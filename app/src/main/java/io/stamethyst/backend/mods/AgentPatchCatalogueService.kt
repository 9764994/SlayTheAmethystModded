package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.backend.github.WattToolkitAcceleratedHttp
import io.stamethyst.config.RuntimePaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class RemoteAgentPatch(
    val name: String,
    val description: String,
    val authorName: String,
    val version: String,
    val patchModId: String,
    val workshopId: String,
    val targetVersions: List<String>,
    val jarName: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String,
)

data class AgentPatchCatalogueSnapshot(
    val patches: List<RemoteAgentPatch>,
    val refreshedAtMs: Long,
    val fromCache: Boolean,
)

data class AgentPatchUploadAuthor(
    val name: String,
    val email: String,
)

data class AgentPatchUploadResult(
    val commitUrl: String,
    val indexUrl: String,
    val indexing: String,
)

object AgentPatchCatalogueService {
    private const val CATALOGUE_URL =
        "https://raw.githubusercontent.com/ModinMobileSTS/SlaytheAmethystPlayerModCatalogue/main/index.json"
    private const val UPLOAD_URL =
        "https://1315061624-boxfc2p5fb.ap-guangzhou.tencentscf.com/api/mod-patches/upload"
    private const val REPOSITORY = "ModinMobileSTS/SlaytheAmethystPlayerModCatalogue"
    private const val MAX_INDEX_BYTES = 4L * 1024L * 1024L
    private const val MAX_JSON_BYTES = 64 * 1024L
    private const val MAX_PATCH_BYTES = 25L * 1024L * 1024L
    private const val MAX_EXPANDED_PATCH_BYTES = 512L * 1024L * 1024L
    private const val MAX_ARCHIVE_ENTRIES = 4096
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val USER_AGENT = "SlayTheAmethyst-AIPatchCatalogue"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val clients = ConcurrentHashMap<String, okhttp3.OkHttpClient>()
    private val parsedCache = ConcurrentHashMap<String, AgentPatchCatalogueSnapshot>()
    private val indexRefreshMutex = Mutex()

    fun readCached(context: Context, parentModId: String): AgentPatchCatalogueSnapshot? {
        val cacheFile = RuntimePaths.agentPatchCatalogueCacheFile(context)
        if (!cacheFile.isFile || cacheFile.length() !in 1..MAX_INDEX_BYTES) return null
        val bytes = runCatching { cacheFile.readBytes() }.getOrNull() ?: return null
        val key = cacheKey(cacheFile, parentModId, sha256(bytes))
        parsedCache[key]?.let { return it.copy(fromCache = true) }
        return runCatching { parseForParent(bytes, parentModId, fromCache = true) }
            .getOrNull()
            ?.also { parsedCache[key] = it.copy(fromCache = true) }
    }

    fun readCachedParentModIds(context: Context): Set<String> {
        val cacheFile = RuntimePaths.agentPatchCatalogueCacheFile(context)
        if (!cacheFile.isFile || cacheFile.length() !in 1..MAX_INDEX_BYTES) return emptySet()
        return runCatching {
            parseParentModIds(cacheFile.readBytes())
        }.getOrDefault(emptySet())
    }

    internal fun parseParentModIds(bytes: ByteArray): Set<String> {
        val root = runCatching { parseIndex(bytes) }.getOrNull() ?: return emptySet()
        val mods = root.getJSONObject("mods")
        return buildSet {
            val keys = mods.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (parseForParent(root, key, fromCache = true).patches.isNotEmpty()) {
                    add(ModManager.normalizeModId(key))
                }
            }
        }
    }

    suspend fun refresh(context: Context, parentModId: String): AgentPatchCatalogueSnapshot {
        return withContext(Dispatchers.IO) {
            indexRefreshMutex.withLock {
                val cacheFile = RuntimePaths.agentPatchCatalogueCacheFile(context)
                val responseBytes = fetchBytes(context, CATALOGUE_URL, MAX_INDEX_BYTES)
                val responseHash = sha256(responseBytes)
                val responseIndex = parseIndex(responseBytes)
                val localBytes = cacheFile.takeIf { it.isFile && it.length() in 1..MAX_INDEX_BYTES }
                    ?.let { runCatching { it.readBytes() }.getOrNull() }
                val localHash = localBytes?.let(::sha256)
                if (localBytes != null && localHash == responseHash) {
                    val key = cacheKey(cacheFile, parentModId, responseHash)
                    parsedCache[key]?.let { return@withLock it.copy(fromCache = true) }
                    return@withLock parseForParent(parseIndex(localBytes), parentModId, fromCache = true)
                        .also { parsedCache[key] = it.copy(fromCache = true) }
                }

                val parsed = parseForParent(responseIndex, parentModId, fromCache = false)
                writeAtomically(cacheFile, responseBytes)
                parsedCache.keys.removeIf { it.startsWith("${cacheFile.absolutePath}:") }
                parsedCache[cacheKey(cacheFile, parentModId, responseHash)] = parsed
                parsed
            }
        }
    }

    suspend fun refreshIndex(context: Context): Boolean = withContext(Dispatchers.IO) {
        indexRefreshMutex.withLock { refreshIndexFile(context) }
    }

    private fun refreshIndexFile(context: Context): Boolean {
        val cacheFile = RuntimePaths.agentPatchCatalogueCacheFile(context)
        val responseBytes = fetchBytes(context, CATALOGUE_URL, MAX_INDEX_BYTES)
        parseIndex(responseBytes)
        val localBytes = cacheFile.takeIf { it.isFile && it.length() in 1..MAX_INDEX_BYTES }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
        if (localBytes != null && sha256(localBytes) == sha256(responseBytes)) return false
        writeAtomically(cacheFile, responseBytes)
        parsedCache.keys.removeIf { it.startsWith("${cacheFile.absolutePath}:") }
        return true
    }

    suspend fun install(
        context: Context,
        parentModId: String,
        patch: RemoteAgentPatch,
    ): AgentPatchModInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val normalizedParent = ModManager.normalizeModId(parentModId)
        if (normalizedParent.isBlank() || patch.size !in 1..MAX_PATCH_BYTES) {
            throw IOException("Invalid AI patch catalogue entry.")
        }
        val targetDir = RuntimePaths.agentModsForModRoot(context, AgentPatchModManager.parentModSegment(normalizedParent))
        if (!targetDir.exists() && !targetDir.mkdirs()) throw IOException("Failed to create AI patch directory.")
        val stablePatchId = "catalogue-${sha256(patch.patchModId.toByteArray()).take(24)}"
        val targetJar = File(targetDir, "$stablePatchId.jar")
        val downloaded = File(targetDir, ".$stablePatchId.${System.nanoTime()}.download")
        val packaged = File(targetDir, ".$stablePatchId.${System.nanoTime()}.tmp")
        try {
            downloadToFile(context, patch.downloadUrl, downloaded, patch.size)
            if (!sha256(downloaded).equals(patch.sha256, ignoreCase = true)) {
                throw IOException("AI patch SHA-256 does not match the catalogue.")
            }
            val manifest = validateRemoteJar(downloaded, normalizedParent, patch)
            val parentDependency = manifest.getJSONArray("dependencies").let { dependencies ->
                (0 until dependencies.length()).map { dependencies.optString(it) }
                    .firstOrNull { ModManager.normalizeModId(it) == normalizedParent }
            } ?: throw IOException("Patch JAR does not depend on the selected mod.")
            repackageWithLocalDescriptor(downloaded, packaged, parentDependency, stablePatchId, patch)
            replaceAtomically(packaged, targetJar)
            AgentPatchModManager.listPackaged(context, normalizedParent)
                .firstOrNull { it.patchId == stablePatchId }
                ?: throw IOException("Installed AI patch failed validation.")
        } finally {
            downloaded.delete()
            packaged.delete()
        }
    }

    suspend fun upload(
        context: Context,
        parentModId: String,
        workshopId: ULong?,
        patch: AgentPatchModInfo,
        author: AgentPatchUploadAuthor,
        consent: Boolean,
        targetVersions: List<String>,
    ): AgentPatchUploadResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (workshopId == null || workshopId == 0uL) {
            throw IOException("This mod has no Workshop ID required by the public patch catalogue.")
        }
        val cleanAuthor = author.name.trim()
        val cleanEmail = author.email.trim()
        if (cleanAuthor.isBlank() || cleanAuthor.length > 120 || cleanAuthor.any { it == '/' || it == '\\' }) {
            throw IOException("Enter a valid public username.")
        }
        if (!EMAIL.matches(cleanEmail) || cleanEmail.length > 254) throw IOException("Enter a valid email address.")
        if (!consent) throw IOException("Public email and redistribution consent is required.")
        if (!patch.jarFile.isFile || patch.jarFile.length() !in 1..MAX_PATCH_BYTES) {
            throw IOException("The packaged AI patch JAR is missing or too large.")
        }

        val sourceZip = createSourceArchive(context, parentModId, patch.patchId)
        try {
            val metadata = JSONObject()
                .put("schemaVersion", 1)
                .put("name", patch.name)
                .put("description", patch.description.ifBlank { "AI patch for $parentModId" })
                .put("version", patch.version.ifBlank { "1.0.0" })
                .put("author", JSONObject().put("name", cleanAuthor).put("email", cleanEmail))
                .put(
                    "target",
                    JSONObject()
                        .put("modId", parentModId)
                        .put("workshopId", workshopId.toString())
                        .put("versions", JSONArray(targetVersions.map(String::trim).filter(String::isNotBlank).distinct().ifEmpty { listOf("*") })),
                )
                .put("jar", patch.jarFile.name)
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", metadata.toString())
                .addFormDataPart("consent", "{\"publicEmail\":true,\"redistribution\":true}")
                .addFormDataPart(
                    "jar", patch.jarFile.name,
                    patch.jarFile.asRequestBody("application/java-archive".toMediaType()),
                )
            sourceZip?.let { file ->
                multipart.addFormDataPart("source", file.name, file.asRequestBody("application/zip".toMediaType()))
            }
            val request = Request.Builder()
                .url(UPLOAD_URL)
                .header("User-Agent", USER_AGENT)
                .post(multipart.build())
                .build()
            val responseBytes = sharedClient(context).newCall(request).execute().use { response ->
                val bytes = response.body?.byteStream()?.let { readBounded(it, 128 * 1024) } ?: ByteArray(0)
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).optString("message") }.getOrNull()
                    throw IOException(detail?.takeIf(String::isNotBlank) ?: "Patch upload failed (HTTP ${response.code}).")
                }
                bytes
            }
            val body = JSONObject(responseBytes.toString(Charsets.UTF_8))
            if (!body.optBoolean("ok")) throw IOException(body.optString("message", "Patch upload failed."))
            AgentPatchUploadResult(
                commitUrl = body.optString("commitUrl").takeIf(String::isNotBlank)
                    ?: throw IOException("Upload completed but returned no commit URL."),
                indexUrl = body.optString("indexUrl"),
                indexing = body.optString("indexing"),
            )
        } finally {
            sourceZip?.delete()
        }
    }

    internal fun parseForParent(
        bytes: ByteArray,
        parentModId: String,
        fromCache: Boolean = false,
    ): AgentPatchCatalogueSnapshot = parseForParent(parseIndex(bytes), parentModId, fromCache)

    private fun parseIndex(bytes: ByteArray): JSONObject {
        if (bytes.isEmpty() || bytes.size > MAX_INDEX_BYTES) throw IOException("Catalogue index is empty or too large.")
        val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw IOException("Catalogue index is not valid JSON.", it) }
        if (root.optInt("schemaVersion") != 3) throw IOException("Unsupported AI patch catalogue schema.")
        if (root.optJSONObject("mods") == null) throw IOException("Catalogue index has no mod entries.")
        return root
    }

    private fun parseForParent(
        root: JSONObject,
        parentModId: String,
        fromCache: Boolean,
    ): AgentPatchCatalogueSnapshot {
        val normalizedParent = ModManager.normalizeModId(parentModId)
        val mods = root.optJSONObject("mods")
        val modEntry = mods?.optJSONObject(normalizedParent)
            ?: mods?.keys()?.asSequence()?.firstOrNull { ModManager.normalizeModId(it) == normalizedParent }
                ?.let { mods.optJSONObject(it) }
            ?: return AgentPatchCatalogueSnapshot(emptyList(), System.currentTimeMillis(), fromCache)
        val workshopId = modEntry.optString("workshopId")
        val patches = modEntry.optJSONArray("patches") ?: JSONArray()
        val result = ArrayList<RemoteAgentPatch>(patches.length())
        for (index in 0 until patches.length()) {
            val entry = patches.optJSONObject(index) ?: continue
            if (entry.optBoolean("example")) continue
            val target = entry.optJSONObject("target") ?: continue
            if (ModManager.normalizeModId(target.optString("modId")) != normalizedParent) continue
            val jarName = entry.optString("jar")
            val url = entry.optString("download_url")
            val size = entry.optLong("size", -1L)
            val patchModId = entry.optString("patchModId")
            val hash = entry.optString("sha256").lowercase(Locale.ROOT)
            if (!jarName.endsWith(".jar", ignoreCase = true) || jarName.contains('/') || jarName.contains('\\')) continue
            if (!patchModId.matches(SAFE_MOD_ID) || size !in 1..MAX_PATCH_BYTES || !SHA256.matches(hash)) continue
            val parsedUrl = url.toHttpUrlOrNull() ?: continue
            if (parsedUrl.scheme != "https" || parsedUrl.host != "raw.githubusercontent.com") continue
            val versions = target.optJSONArray("versions") ?: JSONArray()
            val author = entry.optJSONObject("author")
            result += RemoteAgentPatch(
                name = entry.optString("name").take(120),
                description = entry.optString("description").take(4000),
                authorName = author?.optString("name").orEmpty().take(120),
                version = entry.optString("version").take(120),
                patchModId = patchModId,
                workshopId = target.optString("workshopId", workshopId),
                targetVersions = (0 until versions.length()).mapNotNull { i -> versions.optString(i).takeIf(String::isNotBlank) },
                jarName = jarName,
                downloadUrl = url,
                size = size,
                sha256 = hash,
            )
        }
        return AgentPatchCatalogueSnapshot(
            patches = result.sortedWith(compareBy<RemoteAgentPatch> { it.name.lowercase(Locale.ROOT) }.thenBy { it.version }),
            refreshedAtMs = System.currentTimeMillis(),
            fromCache = fromCache,
        )
    }

    private fun validateRemoteJar(file: File, parentModId: String, patch: RemoteAgentPatch): JSONObject {
        try {
            ZipFile(file).use { zip ->
                if (zip.size() !in 1..MAX_ARCHIVE_ENTRIES) throw IOException("Patch JAR has an invalid entry count.")
                val manifestEntry = zip.getEntry("ModTheSpire.json") ?: throw IOException("Patch JAR has no ModTheSpire.json.")
                if (manifestEntry.size !in 1..MAX_JSON_BYTES) throw IOException("Patch manifest is too large.")
                val manifest = JSONObject(zip.getInputStream(manifestEntry).use { readBounded(it, MAX_JSON_BYTES.toInt()) }.toString(Charsets.UTF_8))
                if (manifest.optString("modid") != patch.patchModId) throw IOException("Patch modid does not match the catalogue.")
                if (manifest.optString("name") != patch.name || manifest.optString("version") != patch.version) {
                    throw IOException("Patch manifest does not match the catalogue metadata.")
                }
                val dependencies = manifest.optJSONArray("dependencies") ?: throw IOException("Patch has no dependency list.")
                if ((0 until dependencies.length()).none { ModManager.normalizeModId(dependencies.optString(it)) == parentModId }) {
                    throw IOException("Patch does not target this mod.")
                }
                if (zip.entries().asSequence().none { it.name.endsWith(".class", ignoreCase = true) }) {
                    throw IOException("Patch JAR contains no compiled classes.")
                }
                var total = 0L
                val names = HashSet<String>(zip.size())
                zip.entries().asSequence().forEach { entry ->
                    if (!names.add(entry.name) || entry.name.startsWith('/') || entry.name.contains('\\') ||
                        entry.name.split('/').any { it == ".." }
                    ) throw IOException("Patch JAR has an unsafe or duplicate entry.")
                    if (entry.size > MAX_EXPANDED_PATCH_BYTES) throw IOException("Patch JAR entry is too large.")
                    total += entry.size.coerceAtLeast(0L)
                    if (total > MAX_EXPANDED_PATCH_BYTES) throw IOException("Patch JAR expands beyond the safety limit.")
                }
                return manifest
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Patch JAR is invalid.", error)
        }
    }

    private fun repackageWithLocalDescriptor(
        source: File,
        output: File,
        parentModId: String,
        patchId: String,
        patch: RemoteAgentPatch,
    ) {
        val descriptor = JSONObject()
            .put("parent_mod_id", parentModId)
            .put("patch_id", patchId)
            .put("patch_mod_id", patch.patchModId)
            .put("name", patch.name)
            .put("version", patch.version)
            .put("description", patch.description)
            .toString(2)
            .toByteArray(Charsets.UTF_8)
        ZipFile(source).use { input ->
            ZipOutputStream(output.outputStream().buffered()).use { zipOut ->
                input.entries().asSequence().forEach { entry ->
                    if (entry.name == "agent-patch.json") return@forEach
                    zipOut.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    input.getInputStream(entry).use { stream ->
                        stream.copyTo(zipOut, DEFAULT_BUFFER_SIZE)
                    }
                    zipOut.closeEntry()
                }
                zipOut.putNextEntry(ZipEntry("agent-patch.json"))
                zipOut.write(descriptor)
                zipOut.closeEntry()
            }
        }
    }

    private fun createSourceArchive(context: Context, parentModId: String, patchId: String): File? {
        val workspace = AgentPatchModManager.resolvePatchWorkspace(context, parentModId, patchId) ?: return null
        val sourceRoot = workspace.patchRoot.resolve("src")
        if (!sourceRoot.isDirectory) return null
        val files = sourceRoot.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty() || files.size > 256) return null
        var bytes = 0L
        val temp = File(context.cacheDir, "ai-patch-source-${System.nanoTime()}.zip")
        ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            files.forEach { file ->
                val entry = file.relativeTo(sourceRoot).invariantSeparatorsPath
                if (entry.startsWith('/') || entry.split('/').any { it == ".." }) throw IOException("Invalid source path.")
                bytes += file.length()
                if (bytes > 10L * 1024L * 1024L) throw IOException("Patch sources exceed 10 MiB.")
                zip.putNextEntry(ZipEntry(entry))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return temp
    }

    private fun fetchBytes(context: Context, url: String, maximum: Long): ByteArray {
        val request = Request.Builder().url(url).get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return sharedClient(context).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Catalogue request failed (HTTP ${response.code}).")
            val length = response.header("Content-Length")?.toLongOrNull()
            if (length != null && length > maximum) throw IOException("Catalogue response is too large.")
            readBounded(response.body?.byteStream() ?: throw IOException("Catalogue response is empty."), maximum.toInt())
        }
    }

    private fun downloadToFile(context: Context, url: String, target: File, expectedSize: Long) {
        val request = Request.Builder().url(url).get().header("User-Agent", USER_AGENT).build()
        sharedClient(context).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Patch download failed (HTTP ${response.code}).")
            val declaredLength = response.header("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength != expectedSize) throw IOException("Patch download size does not match the catalogue.")
            val input = response.body?.byteStream() ?: throw IOException("Patch download is empty.")
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var count = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Patch download was cancelled.")
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                    if (count > expectedSize || count > MAX_PATCH_BYTES) throw IOException("Patch download exceeds its size limit.")
                    output.write(buffer, 0, read)
                }
                if (count != expectedSize) throw IOException("Patch download size is incomplete.")
            }
        }
    }

    private fun sharedClient(context: Context): okhttp3.OkHttpClient {
        val app = context.applicationContext
        val key = RuntimePaths.transientFilesRoot(app).absolutePath
        return clients.getOrPut(key) {
            WattToolkitAcceleratedHttp.createClientPair(
                context = app,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                followRedirects = true,
            ).acceleratedClient
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("Catalogue cache has no parent directory.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create catalogue cache directory.")
        val temp = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            temp.outputStream().buffered().use { it.write(bytes) }
            replaceAtomically(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun replaceAtomically(source: File, target: File) {
        try {
            java.nio.file.Files.move(
                source.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun cacheKey(file: File, parentModId: String, indexHash: String): String =
        "${file.absolutePath}:$indexHash:${ModManager.normalizeModId(parentModId)}"

    private fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximum, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size().toLong() + count > maximum) throw IOException("Response exceeds its size limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val SAFE_MOD_ID = Regex("[A-Za-z0-9._-]{1,120}")
    private val EMAIL = Regex("[^\\s<>@]+@[^\\s<>@]+\\.[^\\s<>@]+")
}
