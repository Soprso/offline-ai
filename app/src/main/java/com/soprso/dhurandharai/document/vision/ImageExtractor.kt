package com.soprso.dhurandharai.document.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.soprso.dhurandharai.document.DocumentStorage
import com.soprso.dhurandharai.document.ExtractedImage
import com.soprso.dhurandharai.document.image.ImageFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Validates stored chat images and produces small WebP thumbnails.
 *
 * The original file is never decoded at full resolution here: bounds are read
 * with inJustDecodeBounds and the thumbnail bitmap is sampled down first. Chat
 * therefore only ever displays the thumbnail; the original is opened lazily by
 * the full-screen viewer.
 */
class ImageExtractor(
    private val context: Context,
    private val preprocessor: ImagePreprocessor,
) {

    /**
     * Returns metadata plus a thumbnail path for a file that has already been
     * copied into persistent image storage. Null means the file is not a
     * readable image (corrupted or unsupported).
     */
    suspend fun processStoredImage(
        file: File,
        attachmentId: String,
    ): ExtractedImage? = withContext(Dispatchers.Default) {
        val dimensions = readDimensions(file) ?: return@withContext null
        val thumbnailPath = createThumbnail(file, attachmentId)

        ExtractedImage(
            id = attachmentId,
            pageNumber = null,
            localPath = DocumentStorage.relativePath(context, file),
            thumbnailPath = thumbnailPath,
            width = dimensions.first,
            height = dimensions.second,
            sizeBytes = file.length(),
        )
    }

    /**
     * Reads original dimensions without decoding the bitmap. EXIF orientation
     * is applied to the returned width/height.
     */
    fun readDimensions(file: File): Pair<Int, Int>? {
        if (!file.isFile || file.length() <= 0L) return null

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return null

        val rotation = runCatching {
            val exif = ExifInterface(file.absolutePath)
            exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        return when (rotation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
            -> height to width
            else -> width to height
        }
    }

    /** Decodes a sampled copy, scales to at most 256 px and writes WebP. */
    private suspend fun createThumbnail(source: File, attachmentId: String): String? {
        val decoded = preprocessor.decodeSampledBitmap(
            context = context,
            uri = null,
            file = source,
            maxDimension = ImageFormat.THUMBNAIL_MAX_DIMENSION * 2,
        ) ?: return null

        val (targetWidth, targetHeight) = ImageFormat.thumbnailDimensions(
            decoded.width,
            decoded.height,
        )
        val scaled = if (decoded.width == targetWidth && decoded.height == targetHeight) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }

        val thumbFile = File(
            DocumentStorage.imageThumbnailsDir(context),
            "thumb_${sanitize(attachmentId)}.webp",
        )

        return try {
            val encoded = encodeWebpUnderBudget(scaled)
            thumbFile.writeBytes(encoded)
            DocumentStorage.relativePath(context, thumbFile)
        } catch (_: Exception) {
            thumbFile.delete()
            null
        } finally {
            if (!scaled.isRecycled) scaled.recycle()
        }
    }

    /** Tries progressively lower WebP quality to stay under the 50 KB budget. */
    private fun encodeWebpUnderBudget(bitmap: Bitmap): ByteArray {
        var quality = 82
        var encoded = ByteArray(0)
        while (quality >= 40) {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, output)
            encoded = output.toByteArray()
            if (encoded.size <= ImageFormat.THUMBNAIL_MAX_BYTES) break
            quality -= 10
        }
        return encoded
    }

    private fun sanitize(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank {
            "image_${max(0, id.hashCode())}"
        }
}
