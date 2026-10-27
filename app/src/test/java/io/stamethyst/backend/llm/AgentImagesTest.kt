package io.stamethyst.backend.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AgentImagesTest {
    private val png = Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)

    @Test
    fun sniffsSupportedImageFormats() {
        assertEquals("image/png", AgentImages.sniffMime(png))
        assertEquals("image/jpeg", AgentImages.sniffMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/gif", AgentImages.sniffMime("GIF89a".toByteArray(Charsets.US_ASCII)))
        assertEquals(
            "image/webp",
            AgentImages.sniffMime("RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) + "WEBP".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun fallsBackToTheFileExtension() {
        assertEquals("image/png", AgentImages.detectMime("screenshot.PNG", byteArrayOf(1, 2, 3)))
        assertEquals("image/jpeg", AgentImages.detectMime("photo.jpeg", ByteArray(0)))
        assertNull(AgentImages.detectMime("notes.txt", "hello".toByteArray()))
        assertNull(AgentImages.detectMime("icon.svg", "<svg/>".toByteArray()))
    }

    @Test
    fun smallImagesPassThroughUntouched() {
        val payload = AgentImages.prepare("shot.png", png)

        assertNotNull(payload)
        assertEquals("image/png", payload!!.mime)
        assertEquals(png.size, payload.bytes)
        assertEquals(Base64.getEncoder().encodeToString(png), payload.base64)
        assertTrue(!payload.downscaled)
    }

    @Test
    fun nonImagesAreRejected() {
        assertNull(AgentImages.prepare("notes.txt", "hello".toByteArray()))
    }

    private companion object {
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
