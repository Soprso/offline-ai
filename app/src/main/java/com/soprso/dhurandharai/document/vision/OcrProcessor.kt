package com.soprso.dhurandharai.document.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.soprso.dhurandharai.document.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * On-device OCR using ML Kit Text Recognition (Latin).
 *
 * The recognition model is provided by Google Play Services (unbundled variant) and
 * downloaded once on first use; after that, recognition runs fully on-device and
 * offline. [isAvailable] reports whether the ML Kit client could be created on this
 * device. Per-image failures (model not yet available, decode error, no text) degrade
 * gracefully to an empty string rather than crashing.
 */
class OcrProcessor(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** True when the ML Kit text recognizer is present on this device. */
    val isAvailable: Boolean
        get() = try {
            recognizer
            true
        } catch (e: Exception) {
            false
        }

    /**
     * Runs OCR on a bitmap and returns the recognized text (blocks/lines joined by
     * newlines). Returns an empty string when OCR is unavailable, the bitmap is
     * empty, or no text is found.
     */
    suspend fun recognizeText(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return@withContext ""
        val rotation = 0 // bitmaps are already EXIF-oriented by ImagePreprocessor
        val image = try {
            InputImage.fromBitmap(bitmap, rotation)
        } catch (e: Exception) {
            return@withContext ""
        }

        suspendCancellableCoroutine { cont ->
            val task = recognizer.process(image)
            task.addOnSuccessListener { visionText ->
                if (cont.isActive) cont.resume(visionText.text ?: "")
            }
            task.addOnFailureListener {
                if (cont.isActive) cont.resume("")
            }
            cont.invokeOnCancellation {
                // The underlying Task has no cheap cancel hook; dropping the result is safe.
            }
        }
    }

    /**
     * Runs OCR on multiple images and returns non-empty results keyed by image id.
     * Images that fail or contain no text are skipped.
     */
    suspend fun recognizeBatch(images: Map<String, Bitmap>): List<OcrResult> =
        withContext(Dispatchers.Default) {
            val results = mutableListOf<OcrResult>()
            for ((id, bitmap) in images) {
                val text = recognizeText(bitmap)
                if (text.isNotBlank()) {
                    results += OcrResult(imageId = id, text = text)
                }
            }
            results
        }
}