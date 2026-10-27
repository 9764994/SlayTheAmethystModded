package io.stamethyst.backend.llm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Image handling for the agent workspace.
 *
 * OpenCode V2 returns images as `file` parts straight from its read tool. This launcher has no
 * separate file-part channel, so the read tools hand back a normalised payload that the gateway
 * re-sends as an image content part on the next user turn.
 */
object AgentImages {
    /** Largest decoded image kept as-is; larger images are downscaled before they reach the model. */
    const val MAX_IMAGE_DIMENSION = 1_568

    /** Wire limit for one attachment. The encoded payload travels twice (tool call + next request). */
    const val MAX_IMAGE_BYTES = 3_500_000

    /** Formats every supported OpenAI-compatible vision endpoint accepts. */
    private val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    /** Detects the image MIME type from magic bytes, which is more reliable than the file extension. */
    fun sniffMime(bytes: ByteArray): String? {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return "image/png"
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 6 && bytes.copyOfRange(0, 3).contentEquals("GIF".toByteArray(Charsets.US_ASCII))) {
            return "image/gif"
        }
        if (bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII))
        ) return "image/webp"
        return null
    }

    fun mimeForName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> null
    }

    /** Falls back to the extension when the file is too short to sniff or the magic bytes are absent. */
    fun detectMime(name: String, bytes: ByteArray): String? =
        sniffMime(bytes)?.takeIf { it in SUPPORTED_MIME_TYPES } ?: mimeForName(name)?.takeIf { it in SUPPORTED_MIME_TYPES }

    /**
     * Normalises [bytes] into something a vision endpoint accepts.
     *
     * Images at or below the dimension and size limits pass through untouched. Larger screenshots
     * are downscaled and re-encoded as WebP so a 1440x3200 PNG capture stays under the wire cap.
     * Returns null when the payload cannot be represented within [MAX_IMAGE_BYTES].
     */
    fun prepare(name: String, bytes: ByteArray): AgentImagePayload? {
        val mime = detectMime(name, bytes) ?: return null
        if (bytes.isEmpty()) return null
        val bounds = runCatching {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it)
            }
        }.getOrNull()
        val width = bounds?.outWidth?.takeIf { it > 0 }
        val height = bounds?.outHeight?.takeIf { it > 0 }
        val oversized = bytes.size > MAX_IMAGE_BYTES ||
            (width != null && height != null && maxOf(width, height) > MAX_IMAGE_DIMENSION)
        if (!oversized) return payload(mime, bytes, width, height, downscaled = false)

        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return null
        val scale = minOf(
            1f,
            MAX_IMAGE_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1),
        )
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        // GIF frame selection and transparency are not preserved; WebP at high quality keeps text legible.
        val encoded = runCatching {
            ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.WEBP, 88, output)
                output.toByteArray()
            }
        }.getOrNull() ?: return null
        if (encoded.size > MAX_IMAGE_BYTES) return null
        return payload("image/webp", encoded, scaled.width, scaled.height, downscaled = true)
    }

    private fun payload(
        mime: String,
        bytes: ByteArray,
        width: Int?,
        height: Int?,
        downscaled: Boolean,
    ): AgentImagePayload = AgentImagePayload(
        mime = mime,
        base64 = Base64.getEncoder().encodeToString(bytes),
        bytes = bytes.size,
        width = width,
        height = height,
        downscaled = downscaled,
    )
}

data class AgentImagePayload(
    val mime: String,
    val base64: String,
    val bytes: Int,
    val width: Int?,
    val height: Int?,
    val downscaled: Boolean,
)
