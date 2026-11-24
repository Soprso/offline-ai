package com.soprso.dhurandharai.document.image

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure, Android-free image helpers shared by storage, thumbnails, the chat
 * preview and JVM unit tests. Keeping this object free of platform types lets
 * the sizing/extension rules be tested without an emulator.
 */
object ImageFormat {

    /** Longest thumbnail edge in pixels. */
    const val THUMBNAIL_MAX_DIMENSION = 256

    /** Soft budget for a 256 px WebP thumbnail. */
    const val THUMBNAIL_MAX_BYTES = 50 * 1024

    /** Hard cap for originals decoded by the full-screen viewer. */
    const val FULL_VIEW_MAX_DIMENSION = 4096

    private val extensionToMime = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
    )

    private val mimeToExtension = mapOf(
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/bmp" to "bmp",
        "image/heic" to "heic",
        "image/heif" to "heif",
    )

    fun isImageMime(mimeType: String?): Boolean =
        mimeType?.lowercase()?.startsWith("image/") == true

    fun isSupportedImageMime(mimeType: String?): Boolean =
        mimeToExtension.containsKey(mimeType?.lowercase())

    /** Stable file extension for a MIME type, falling back to a display name. */
    fun extensionForMime(mimeType: String?, preferredName: String? = null): String {
        val fromMime = mimeToExtension[mimeType?.lowercase()]
        if (fromMime != null) return fromMime

        val fromName = preferredName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it in extensionToMime }
        return fromName ?: "img"
    }

    fun mimeTypeForFileName(name: String?): String? {
        val extension = name
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?: return null
        return extensionToMime[extension]
    }

    fun mimeTypeForExtension(extension: String?): String? =
        extension?.lowercase()?.let { extensionToMime[it] }

    /**
     * Scales [width] x [height] down so the longest edge is at most [maxEdge].
     * Never upscales and never returns a zero dimension.
     */
    fun thumbnailDimensions(
        width: Int,
        height: Int,
        maxEdge: Int = THUMBNAIL_MAX_DIMENSION,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1 to 1
        val longest = max(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toFloat() / longest.toFloat()
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Power-of-two sample size so a decode stays at or below [requestedEdge]. */
    fun calculateInSampleSize(
        width: Int,
        height: Int,
        requestedEdge: Int,
    ): Int {
        if (width <= 0 || height <= 0 || requestedEdge <= 0) return 1
        var sample = 1
        while (width / sample > requestedEdge || height / sample > requestedEdge) {
            sample *= 2
        }
        return sample
    }

    /** Safe default name for the Android "save image" document picker. */
    fun exportFileName(id: String, mimeType: String?): String {
        val safeId = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { "image" }
            .take(32)
        return "offline-ai-$safeId.${extensionForMime(mimeType)}"
    }
}
