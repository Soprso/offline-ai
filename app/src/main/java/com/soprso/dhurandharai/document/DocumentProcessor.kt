package com.soprso.dhurandharai.document

import android.content.Context
import android.net.Uri
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
    ): DocumentAttachment {
        val sizeBytes = sizeOfUri(uri) ?: 0L
        val attachmentId = UUID.randomUUID().toString()
        val category = AttachmentMime.category(mimeType)

        // Images go to files/images/originals; documents stay in files/documents.
        val localFile: File
        val storedPath: String
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

        // Build initial attachment record.
        var attachment = DocumentAttachment(
            id = attachmentId,
            conversationId = conversationId,
            messageId = messageId,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            contentUri = uri.toString(),
            localPath = storedPath,
            thumbnailPath = null,
            status = DocumentAttachmentStatus.Waiting,
        )

        when (category) {
            AttachmentCategory.Image -> {
                attachment = attachment.copy(status = DocumentAttachmentStatus.ExtractingImages)
                val extracted = imageExtractor.processStoredImage(localFile, attachment.id)
                if (extracted == null) {
                    storage.deletePath(context, storedPath)
                    attachment = attachment.copy(
                        status = DocumentAttachmentStatus.Failed,
                        error = AttachmentError(
                            "INVALID_IMAGE",
                            "The selected file is not a readable image.",
                            true,
                        ),
                    )
                    return attachment.copy(processedAt = System.currentTimeMillis())
                }

                attachment = attachment.copy(
                    status = DocumentAttachmentStatus.Ready,
                    imageCount = 1,
                    extractedText = null,
                    pageCount = 1,
                    thumbnailPath = extracted.thumbnailPath,
                    width = extracted.width,
                    height = extracted.height,
                    processedAt = System.currentTimeMillis(),
                )
                // Run OCR on the image if available.
                if (ocrProcessor.isAvailable) {
                    attachment = attachment.copy(status = DocumentAttachmentStatus.RunningOcr)
                    val bitmap = ImagePreprocessor.decodeSampledBitmap(
                        context = context,
                        uri = null,
                        file = localFile,
                    )
                    if (bitmap != null) {
                        try {
                            val ocrText = ocrProcessor.recognizeText(bitmap)
                            attachment = attachment.copy(
                                ocrText = if (ocrText.isNotBlank()) ocrText else null,
                                status = DocumentAttachmentStatus.Ready,
                            )
                        } finally {
                            ImagePreprocessor.recycle(bitmap)
                        }
                    }
                }
            }

            else -> {
                val extractor = router.route(mimeType)
                if (extractor == null) {
                attachment = attachment.copy(
                    status = DocumentAttachmentStatus.Failed,
                    error = AttachmentError("UNSUPPORTED_FORMAT", "No extractor for MIME type $mimeType", false),
                )
                } else {
                    attachment = attachment.copy(status = DocumentAttachmentStatus.ExtractingText)
                    attachment = extractor(uri, localFile, attachment)

                    // For PDFs, also run OCR on pages if text extraction was poor.
                    if (mimeType.lowercase() == AttachmentMime.PDF && ocrProcessor.isAvailable) {
                        attachment = attachment.copy(status = DocumentAttachmentStatus.RunningOcr)
                        val ocrText = attachment.ocrText
                        if (ocrText.isNullOrBlank() && attachment.extractedText?.isBlank() == true) {
                            // Try OCR on first few pages as fallback
                            val firstPageBitmap = pageRenderer.renderPage(localFile, 0, maxDimension = 1024)
                            if (firstPageBitmap != null) {
                                try {
                                    val fallbackText = ocrProcessor.recognizeText(firstPageBitmap)
                                    attachment = attachment.copy(
                                        ocrText = if (fallbackText.isNotBlank()) fallbackText else null,
                                    )
                                } finally {
                                    ImagePreprocessor.recycle(firstPageBitmap)
                                }
                            }
                        }
                    }
                }
            }
        }

        return attachment.copy(processedAt = System.currentTimeMillis())
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
                } else {
                    null
                }
            } ?: null
        } catch (e: Exception) {
            null
        }
    }
}

/** Alias for AttachmentStatus to avoid confusion in processor. */
private typealias DocumentAttachmentStatus = AttachmentStatus
