package com.soprso.dhurandharai.document

import android.content.Context
import android.net.Uri
import android.util.Log
import com.soprso.dhurandharai.document.extractors.*
import com.soprso.dhurandharai.document.image.ImageFormat
import com.soprso.dhurandharai.document.vision.*
import java.io.File
import java.util.UUID

/** Orchestrates document processing: extract text, images, OCR, build context. */
class DocumentProcessor(
    private val context: Context,
    private val router: DocumentRouter,
    private val pageRenderer: PageRenderer,
    private val ocrProcessor: OcrProcessor,
    private val imageExtractor: ImageExtractor,
) {

    private val storage = DocumentStorage

    /**
     * Processes a single attachment end-to-end.
     * Copies the content URI to local storage, runs the appropriate extractor,
     * and performs OCR on images / PDF pages.
     */
    suspend fun processAttachment(
        uri: Uri,
        fileName: String,
        mimeType: String,
        conversationId: String,
        messageId: String?,
        onStatus: (suspend (DocumentAttachment) -> Unit)? = null,
    ): DocumentAttachment {
        val sizeBytes = sizeOfUri(uri) ?: 0L
        val attachmentId = UUID.randomUUID().toString()
        val category = AttachmentMime.category(mimeType)

        var attachment = DocumentAttachment(
            id = attachmentId,
            conversationId = conversationId,
            messageId = messageId,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            contentUri = uri.toString(),
            localPath = null,
            thumbnailPath = null,
            status = AttachmentStatus.Selected,
        )
        onStatus?.invoke(attachment)

        if (category == null) {
            return attachment.copy(
                status = AttachmentStatus.Unsupported,
                error = AttachmentError("UNSUPPORTED_FORMAT", "Unsupported file type: $mimeType", false),
                processedAt = System.currentTimeMillis(),
            ).also { onStatus?.invoke(it) }
        }

        val localFile: File
        val storedPath: String
        try {
            if (category == AttachmentCategory.Image) {
                localFile = storage.copyImageUriToLocal(
                    context = context,
                    uri = uri,
                    imageId = attachmentId,
                    extension = ImageFormat.extensionForMime(mimeType, fileName),
                )
                storedPath = storage.relativePath(context, localFile)
            } else {
                localFile = storage.copyUriToLocal(context, uri, fileName)
                storedPath = localFile.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "Attachment $attachmentId copy failed", e)
            return attachment.copy(
                status = AttachmentStatus.Failed,
                error = AttachmentError("COPY_FAILED", "Could not save attachment locally: ${e.message ?: "unknown error"}", true),
                processedAt = System.currentTimeMillis(),
            ).also { onStatus?.invoke(it) }
        }

        attachment = attachment.copy(
            localPath = storedPath,
            status = AttachmentStatus.Saved,
        )
        onStatus?.invoke(attachment)

        attachment = when (category) {
            AttachmentCategory.Image -> processImage(attachment, localFile, onStatus)
            else -> processDocument(attachment, localFile, uri, mimeType, onStatus)
        }

        val finalAttachment = attachment.copy(processedAt = System.currentTimeMillis())
        Log.i(
            TAG,
            "attachment=${finalAttachment.id} stage=complete status=${finalAttachment.status} " +
                "chars=${finalAttachment.extractedText?.length ?: 0} ocrChars=${finalAttachment.ocrText?.length ?: 0} " +
                "pages=${finalAttachment.pageCount} images=${finalAttachment.imageCount}"
        )
        onStatus?.invoke(finalAttachment)
        return finalAttachment
    }

    private suspend fun processImage(
        attachment: DocumentAttachment,
        localFile: File,
        onStatus: (suspend (DocumentAttachment) -> Unit)?,
    ): DocumentAttachment {
        var current = attachment.copy(status = AttachmentStatus.ExtractingImages)
        onStatus?.invoke(current)

        val extracted = imageExtractor.processStoredImage(localFile, current.id)
        if (extracted == null) {
            DocumentStorage.deletePath(context, current.localPath)
            return current.copy(
                status = AttachmentStatus.Failed,
                localPath = null,
                thumbnailPath = null,
                error = AttachmentError("INVALID_IMAGE", "The selected file is not a readable image.", true),
            )
        }

        current = current.copy(
            imageCount = 1,
            pageCount = 1,
            thumbnailPath = extracted.thumbnailPath,
            width = extracted.width,
            height = extracted.height,
        )

        if (!ocrProcessor.isAvailable) {
            return current.copy(
                status = AttachmentStatus.PartiallyProcessed,
                error = AttachmentError(
                    "OCR_UNAVAILABLE",
                    "OCR model is not available offline on this device. Image saved without text extraction.",
                    true,
                ),
            )
        }

        current = current.copy(status = AttachmentStatus.RunningOcr)
        onStatus?.invoke(current)
        val bitmap = ImagePreprocessor.decodeSampledBitmap(context = context, uri = null, file = localFile)
        if (bitmap == null) {
            return current.copy(
                status = AttachmentStatus.PartiallyProcessed,
                error = AttachmentError("IMAGE_DECODE_FAILED", "Image could not be decoded for OCR.", true),
            )
        }
        try {
            val ocr = ocrProcessor.recognizeDetailed(bitmap)
            current = when {
                ocr.error != null -> current.copy(
                    status = AttachmentStatus.PartiallyProcessed,
                    error = AttachmentError("OCR_FAILED", ocr.error, true),
                )
                ocr.text.isNotBlank() -> current.copy(
                    status = AttachmentStatus.Ready,
                    ocrText = ocr.text,
                    error = null,
                )
                else -> current.copy(
                    status = AttachmentStatus.Ready,
                    ocrText = null,
                    error = null,
                )
            }
        } finally {
            ImagePreprocessor.recycle(bitmap)
        }
        return current
    }

    private suspend fun processDocument(
        attachment: DocumentAttachment,
        localFile: File,
        uri: Uri,
        mimeType: String,
        onStatus: (suspend (DocumentAttachment) -> Unit)?,
    ): DocumentAttachment {
        val extractor = router.route(mimeType)
            ?: return attachment.copy(
                status = AttachmentStatus.Unsupported,
                error = AttachmentError("UNSUPPORTED_FORMAT", "No extractor for MIME type $mimeType", false),
            )

        var current = attachment.copy(status = AttachmentStatus.ExtractingText)
        onStatus?.invoke(current)
        current = try {
            extractor(uri, localFile, current)
        } catch (e: Exception) {
            Log.w(TAG, "Attachment ${attachment.id} extraction failed", e)
            current.copy(
                status = AttachmentStatus.Failed,
                error = AttachmentError("EXTRACTION_FAILED", "Unable to read this file: ${e.message ?: "unknown error"}", true),
            )
        }

        // PDF fallback OCR when no selectable text was extracted.
        if (mimeType.lowercase() == AttachmentMime.PDF &&
            current.status != AttachmentStatus.Failed &&
            current.extractedText.isNullOrBlank()
        ) {
            if (ocrProcessor.isAvailable) {
                current = current.copy(status = AttachmentStatus.RunningOcr)
                onStatus?.invoke(current)
                val ocrText = ocrScannedPdf(localFile)
                current = if (ocrText.isNotBlank()) {
                    current.copy(status = AttachmentStatus.Ready, ocrText = ocrText, error = null)
                } else {
                    current.copy(
                        status = AttachmentStatus.PartiallyProcessed,
                        error = AttachmentError("NO_TEXT_FOUND", "No selectable text or OCR text was detected in this PDF.", true),
                    )
                }
            } else {
                current = current.copy(
                    status = AttachmentStatus.PartiallyProcessed,
                    error = AttachmentError("OCR_UNAVAILABLE", "PDF has no selectable text and OCR model is unavailable offline.", true),
                )
            }
        }

        return current
    }

    private suspend fun ocrScannedPdf(pdfFile: File): String {
        val pageCount = pageRenderer.pageCount(pdfFile).coerceAtMost(20)
        val sb = StringBuilder()
        for (i in 0 until pageCount) {
            val bitmap = pageRenderer.renderPage(pdfFile, i, maxDimension = 1024) ?: continue
            try {
                val result = ocrProcessor.recognizeDetailed(bitmap)
                if (result.text.isNotBlank()) {
                    sb.append("\n--- PAGE ${i + 1} ---\n").append(result.text)
                }
            } finally {
                ImagePreprocessor.recycle(bitmap)
            }
        }
        return sb.toString().trim()
    }

    private fun sizeOfUri(uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0) cursor.getLong(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "DocumentProcessor"
    }
}
