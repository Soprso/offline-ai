package com.soprso.dhurandharai.document.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFormatTest {

    @Test
    fun thumbnailDimensionsDownscalesLongEdge() {
        val (w, h) = ImageFormat.thumbnailDimensions(1024, 768, 256)
        assertEquals(256, w)
        assertEquals(192, h)
    }

    @Test
    fun thumbnailDimensionsNoUpscale() {
        val (w, h) = ImageFormat.thumbnailDimensions(100, 100, 256)
        assertEquals(100, w)
        assertEquals(100, h)
    }

    @Test
    fun thumbnailDimensionsMinimumOne() {
        val (w, h) = ImageFormat.thumbnailDimensions(0, 0, 256)
        assertTrue(w >= 1 && h >= 1)
    }

    @Test
    fun extensionForMimeFromMimeType() {
        assertEquals("jpg", ImageFormat.extensionForMime("image/jpeg"))
        assertEquals("png", ImageFormat.extensionForMime("image/png"))
        assertEquals("webp", ImageFormat.extensionForMime("image/webp"))
    }

    @Test
    fun extensionForMimeFromFileName() {
        assertEquals("jpeg", ImageFormat.extensionForMime(null, "photo.jpeg"))
        assertEquals("png", ImageFormat.extensionForMime(null, "screenshot.png"))
        assertEquals("img", ImageFormat.extensionForMime(null, "unknown.xyz"))
    }

    @Test
    fun mimeTypeForFileName() {
        assertEquals("image/jpeg", ImageFormat.mimeTypeForFileName("photo.jpg"))
        assertEquals("image/png", ImageFormat.mimeTypeForFileName("image.PNG"))
        assertNull(ImageFormat.mimeTypeForFileName("document.pdf"))
    }

    private fun assertNull(value: String?) {
        org.junit.Assert.assertNull(value)
    }
}
