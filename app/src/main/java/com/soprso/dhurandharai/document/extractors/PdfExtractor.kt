package com.soprso.dhurandharai.document.extractors

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.soprso.dhurandharai.document.DocumentMetadata
import com.soprso.dhurandharai.document.AttachmentError
import com.soprso.dhurandharai.document.AttachmentStatus
import com.soprso.dhurandharai.document.OcrResult
import com.soprso.dhurandharai.document.DocumentStorage
import com.soprso.dhurandharai.document.vision.ImageExtractor
import com.soprso.dhurandharai.document.vision.OcrProcessor
import com.soprso.dhurandharai.document.vision.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Extracts text from PDFs by rendering pages and running OCR (Level A). */
class PdfExtractor(
    private val context: Context,
    private val pageRenderer: PageRenderer,
    private val ocrProcessor: OcrProcessor,
    private val imageExtractor: ImageExtractor,
) {

    companion object {
        const val MAX_PAGES_OCR = 50
        const val OCR_TEXT_THRESHOLD = 20 // chars per page below which we consider it "scanned"
    }

    suspend fun extract(
        uri: Uri?,
        file: File?,
        attachment: DocumentMetadata,
    ): DocumentMetadata = withContext(Dispatchers.Default) {
        val pdfFile = file ?: File(localPathFor(uri, context) ?: return@withContext attachment.copy(
            status = AttachmentStatus.Failed,
            error = AttachmentError("NO_LOCAL_FILE", "PDF file is not available locally", true),
        ))

        if (!pdfFile.exists()) {
            return@withContext attachment.copy(
                status = AttachmentStatus.Failed,
                error = AttachmentError("FILE_NOT_FOUND", "PDF file does not exist", true),
            )
        }

        val pageCount = pageRenderer.pageCount(pdfFile)
        val pagesToProcess = pageCount.coerceAtMost(MAX_PAGES_OCR)

        val textBuilder = StringBuilder()
        var totalOcrText = ""
        var ocrResults = mutableListOf<OcrResult>()

        for (i in 0 until pagesToProcess) {
            val bitmap = pageRenderer.renderPage(pdfFile, i, maxDimension = 1024)
            if (bitmap != null) {
                try {
                    val pageText = ocrProcessor.recognizeText(bitmap)
                    textBuilder.append("\n--- PAGE ${i + 1} ---\n")
                    textBuilder.append(pageText)
                    if (pageText.isNotBlank()) {
                        ocrResults += OcrResult(
                            imageId = "page_${i + 1}",
                            text = pageText,
                            pageNumber = i + 1,
                        )
                        totalOcrText += pageText + "\n"
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

        val extracted = textBuilder.toString().trim()
        val hasUsableText = extracted.length > (pagesToProcess * OCR_TEXT_THRESHOLD)

        attachment.copy(
            status = AttachmentStatus.Ready,
            extractedText = if (hasUsableText) extracted else null,
            ocrText = totalOcrText.takeIf { it.isNotBlank() },
            pageCount = pageCount,
            processedAt = System.currentTimeMillis(),
        )
    }

    private fun localPathFor(uri: Uri?, context: Context): String? {
        if (uri == null) return null
        // For PDFs, we expect a local copy in DocumentStorage.originalsDir.
        // The caller should have already copied the URI to local storage.
        return null
    }
}
