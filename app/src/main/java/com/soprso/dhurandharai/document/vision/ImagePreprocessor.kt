package com.soprso.dhurandharai.document.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.soprso.dhurandharai.document.DocumentStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.max

/** Downsamples bitmaps to keep memory usage bounded during OCR/vision processing. */
object ImagePreprocessor {

    /** Maximum dimension for images sent to OCR. Larger images are downscaled. */
    const val MAX_OCR_DIMENSION = 1600

    /** Target dimension for thumbnails. */
    const val THUMBNAIL_DIMENSION = 256

    /**
     * Decodes a bitmap from a content URI or file, respecting EXIF orientation,
     * and downsamples if it exceeds [maxDimension].
     * Returns null if the image cannot be decoded.
     */
    suspend fun decodeSampledBitmap(
        context: Context,
        uri: Uri?,
        file: File?,
        maxDimension: Int = MAX_OCR_DIMENSION,
    ): Bitmap? = withContext(Dispatchers.Default) {
        val input: InputStream? = if (uri != null) {
            context.contentResolver.openInputStream(uri)
        } else if (file != null && file.exists()) {
            java.io.FileInputStream(file)
        } else {
            null
        }
        input?.use { stream ->
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(stream, null, options)

            val outWidth = options.outWidth
            val outHeight = options.outHeight
            if (outWidth <= 0 || outHeight <= 0) {
                return@withContext null
            } else {
                var sampleSize = 1
                while (outWidth / sampleSize > maxDimension || outHeight / sampleSize > maxDimension) {
                    sampleSize *= 2
                }

                val input2: InputStream? = if (uri != null) {
                    context.contentResolver.openInputStream(uri)
                } else if (file != null && file.exists()) {
                    java.io.FileInputStream(file)
                } else {
                    null
                }
                input2?.use { stream2 ->
                    val opts2 = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    val bitmap = BitmapFactory.decodeStream(stream2, null, opts2)
                    val filePath = file?.absolutePath ?: uri?.path ?: ""
                    val oriented = applyExifOrientation(bitmap, filePath)
                    return@withContext oriented
                }
            }
        } ?: null
    }

    /** Creates a thumbnail bitmap bounded by [THUMBNAIL_DIMENSION]. */
    suspend fun createThumbnail(
        context: Context,
        uri: Uri?,
        file: File?,
    ): Bitmap? = decodeSampledBitmap(context, uri, file, THUMBNAIL_DIMENSION)

    /** Applies EXIF orientation to a bitmap. */
    private fun applyExifOrientation(bitmap: Bitmap?, filePath: String): Bitmap {
        if (bitmap == null) return Bitmap.createBitmap(0, 0, Bitmap.Config.ARGB_8888)
        return try {
            val exif = ExifInterface(filePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postScale(-1f, 1f)
                    matrix.postRotate(90f)
                }
                else -> Unit
            }
            if (!matrix.isIdentity()) {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (_: Exception) {
            bitmap
        }
    }

    /** Recycles a bitmap if it is mutable and not already recycled. */
    fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled && bitmap.isMutable) {
            bitmap.recycle()
        }
    }
}
