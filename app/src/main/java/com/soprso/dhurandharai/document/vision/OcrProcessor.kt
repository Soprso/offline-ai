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

data class DetailedOcrResult(
    val text: String = "",
    val blocks: Int = 0,
    val lines: Int = 0,
    val error: String? = null,
)

/**
 * On-device OCR using ML Kit Text Recognition (Latin).
 *
 * Uses Google Play Services unbundled model. Recognition runs on-device after
 * model availability; if model is unavailable, callers receive an explicit error.
 */
class OcrProcessor(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** True when the ML Kit text recognizer client can be created. */
    val isAvailable: Boolean
        get() = try {
            recognizer
            true
        } catch (_: Exception) {
            false
        }

    suspend fun recognizeDetailed(bitmap: Bitmap): DetailedOcrResult = withContext(Dispatchers.Default) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return@withContext DetailedOcrResult()
        val image = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (e: Exception) {
            return@withContext DetailedOcrResult(error = "OCR input image could not be created: ${e.message ?: "unknown"}")
        }
        suspendCancellableCoroutine { cont ->
            val task = recognizer.process(image)
            task.addOnSuccessListener { visionText ->
                if (!cont.isActive) return@addOnSuccessListener
                val blocks = visionText.textBlocks?.size ?: 0
                val lines = visionText.textBlocks?.sumOf { it.lines.size } ?: 0
                cont.resume(
                    DetailedOcrResult(
                        text = visionText.text.orEmpty(),
                        blocks = blocks,
                        lines = lines,
                        error = null,
                    )
                )
            }
            task.addOnFailureListener { e ->
                if (cont.isActive) {
                    cont.resume(
                        DetailedOcrResult(
                            error = "OCR failed: ${e.message ?: e.javaClass.simpleName}",
                        )
                    )
                }
            }
            cont.invokeOnCancellation { }
        }
    }

    /**
     * Runs OCR on a bitmap and returns recognized text. Empty string means no text
     * or failure; prefer [recognizeDetailed] for status-aware flows.
     */
    suspend fun recognizeText(bitmap: Bitmap): String = recognizeDetailed(bitmap).text

    /**
     * Runs OCR on multiple images and returns non-empty results keyed by image id.
     */
    suspend fun recognizeBatch(images: Map<String, Bitmap>): List<OcrResult> =
        withContext(Dispatchers.Default) {
            val results = mutableListOf<OcrResult>()
            for ((id, bitmap) in images) {
                val detailed = recognizeDetailed(bitmap)
                if (detailed.text.isNotBlank()) {
                    results += OcrResult(imageId = id, text = detailed.text)
                }
            }
            results
        }
}