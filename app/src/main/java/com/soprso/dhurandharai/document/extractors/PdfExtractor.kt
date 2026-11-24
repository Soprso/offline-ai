package com.soprso.dhurandharai.document.extractors

import android.content.Context
import android.net.Uri
import com.soprso.dhurandharai.document.*
import com.soprso.dhurandharai.document.vision.ImageExtractor
import com.soprso.dhurandharai.document.vision.OcrProcessor
import com.soprso.dhurandharai.document.vision.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF extraction with page-aware OCR fallback.
 *
 * Selectable-text extraction is attempted via PdfBox-Android when available on
 * classpath; otherwise falls back to page rendering + OCR. This avoids adding a
 * hard dependency before evaluating APK/maintenance impact in this environment.
 */
class PdfExtractor(
    private val context: Context,
    private val pageRenderer: PageRenderer,
    private val ocrProcessor: OcrProcessor,
    private val imageExtractor: ImageExtractor,
) {

    companion object {
        const val MAX_PAGES = 50
        const val MIN_SELECTABLE_CHARS_PER_PAGE = 20
    }

    suspend fun extract(
        uri: Uri?,
        file: File?,
        attachment: DocumentMetadata,
    ): DocumentMetadata = withContext(Dispatchers.Default) {
        val pdfFile = file ?: return@withContext attachment.copy(
            status = AttachmentStatus.Failed,
            error = AttachmentError("NO_LOCAL_FILE", "PDF file is not available locally", true),
        )
        if (!pdfFile.exists()) {
            return@withContext attachment.copy(
                status = AttachmentStatus.Failed,
                error = AttachmentError("FILE_NOT_FOUND", "PDF file does not exist", true),
            )
        }

        val pageCount = pageRenderer.pageCount(pdfFile)
        val pagesToProcess = pageCount.coerceAtMost(MAX_PAGES)
        val selectable = extractSelectableTextByReflection(pdfFile, pagesToProcess)

        val extracted = StringBuilder()
        val ocr = StringBuilder()
        var ocrPages = 0
        var missingTextPages = 0

        for (pageIndex in 0 until pagesToProcess) {
            val pageNo = pageIndex + 1
            val pageText = selectable.getOrNull(pageIndex)?.trim().orEmpty()
            val hasSelectable = pageText.length >= MIN_SELECTABLE_CHARS_PER_PAGE
            if (hasSelectable) {
                extracted.append("\n--- PAGE $pageNo ---\n").append(pageText).append("\n")
                continue
            }

            // Scanned/empty page: render and OCR.
            val bitmap = pageRenderer.renderPage(pdfFile, pageIndex, maxDimension = 1280)
            if (bitmap == null) {
                missingTextPages++
                continue
            }
            try {
                if (ocrProcessor.isAvailable) {
                    val result = ocrProcessor.recognizeDetailed(bitmap)
                    if (result.text.isNotBlank()) {
                        ocr.append("\n--- PAGE $pageNo [OCR] ---\n").append(result.text.trim()).append("\n")
                        ocrPages++
                    } else if (result.error != null) {
                        missingTextPages++
                    } else {
                        missingTextPages++
                    }
                } else {
                    missingTextPages++
                }
            } finally {
                bitmap.recycle()
            }
        }

        val extractedText = extracted.toString().trim().ifBlank { null }
        val ocrText = ocr.toString().trim().ifBlank { null }
        val status = when {
            extractedText != null && ocrText != null -> AttachmentStatus.Ready
            extractedText != null -> AttachmentStatus.Ready
            ocrText != null -> AttachmentStatus.Ready
            pagesToProcess == 0 -> AttachmentStatus.Failed
            !ocrProcessor.isAvailable -> AttachmentStatus.PartiallyProcessed
            missingTextPages > 0 -> AttachmentStatus.PartiallyProcessed
            else -> AttachmentStatus.PartiallyProcessed
        }
        val error = when {
            status == AttachmentStatus.Failed -> AttachmentError("PDF_EMPTY", "PDF has no readable pages.", true)
            status == AttachmentStatus.PartiallyProcessed && !ocrProcessor.isAvailable ->
                AttachmentError("OCR_UNAVAILABLE", "PDF has pages without selectable text and OCR model is unavailable offline.", true)
            status == AttachmentStatus.PartiallyProcessed && extractedText == null && ocrText == null ->
                AttachmentError("NO_TEXT_FOUND", "No selectable text or OCR text was detected in processed PDF pages.", true)
            else -> null
        }

        attachment.copy(
            status = status,
            extractedText = extractedText,
            ocrText = ocrText,
            pageCount = pageCount,
            imageCount = 0,
            error = error,
            processedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Attempts PdfBox-Android text extraction via reflection so this build does
     * not hard-fail when the dependency is absent. Returns empty list when unavailable.
     */
    private fun extractSelectableTextByReflection(pdfFile: File, pages: Int): List<String> {
        return try {
            val loaderClass = Class.forName("com.tom_roush.pdfbox.pdmodel.PDDocument")
            val loadMethod = loaderClass.getMethod("load", File::class.java)
            val doc = loadMethod.invoke(null, pdfFile)
            try {
                val stripperClass = Class.forName("com.tom_roush.pdfbox.text.PDFTextStripper")
                val stripper = stripperClass.getDeclaredConstructor().newInstance()
                val setStart = stripperClass.getMethod("setStartPage", Int::class.javaPrimitiveType)
                val setEnd = stripperClass.getMethod("setEndPage", Int::class.javaPrimitiveType)
                val getText = stripperClass.getMethod("getText", loaderClass)
                (0 until pages).map { index ->
                    setStart.invoke(stripper, index + 1)
                    setEnd.invoke(stripper, index + 1)
                    (getText.invoke(stripper, doc) as? String).orEmpty()
                }
            } finally {
                loaderClass.getMethod("close").invoke(doc)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
