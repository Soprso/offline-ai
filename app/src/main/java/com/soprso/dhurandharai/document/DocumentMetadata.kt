package com.soprso.dhurandharai.document

/** Supported attachment MIME types. */
object AttachmentMime {
    const val PDF = "application/pdf"
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    const val TXT = "text/plain"
    const val MARKDOWN = "text/markdown"
    const val CSV = "text/csv"
    const val JPEG = "image/jpeg"
    const val JPG = "image/jpg"
    const val PNG = "image/png"
    const val WEBP = "image/webp"

    val all: Set<String> = setOf(PDF, DOCX, PPTX, TXT, MARKDOWN, CSV, JPEG, JPG, PNG, WEBP)

    fun isSupported(mime: String?): Boolean = mime != null && all.contains(mime.lowercase())

    fun category(mime: String?): AttachmentCategory? {
        return when (mime?.lowercase()) {
            PDF -> AttachmentCategory.Document
            DOCX, PPTX -> AttachmentCategory.Document
            TXT, MARKDOWN -> AttachmentCategory.Text
            CSV -> AttachmentCategory.Spreadsheet
            JPEG, JPG, PNG, WEBP -> AttachmentCategory.Image
            else -> null
        }
    }
}

enum class AttachmentCategory {
    Document, Text, Spreadsheet, Image
}

/** Processing states for an attachment. */
enum class AttachmentStatus {
    Selected,
    Saved,
    Processing,
    ExtractingText,
    ExtractingImages,
    RunningOcr,
    PreparingContext,
    Ready,
    PartiallyProcessed,
    Failed,
    Unsupported,
    VisionRequired,
}

data class AttachmentError(
    val code: String,
    val message: String,
    val recoverable: Boolean = true
)

data class AttachmentProcessingMetrics(
    val extractedChars: Int = 0,
    val ocrChars: Int = 0,
    val pagesProcessed: Int = 0,
    val contextChars: Int = 0,
)

/** Metadata for a single attached document. Stored in Room. */
data class DocumentAttachment(
    val id: String,
    val conversationId: String,
    val messageId: String?,
    /** Original file name as selected by the user. */
    val fileName: String,
    /** MIME type determined from extension/content. */
    val mimeType: String,
    /** Size in bytes. */
    val sizeBytes: Long,
    /** Content URI (persisted via takePersistableUriPermission where possible). */
    val contentUri: String?,
    /** Local copy path in app-private storage. */
    val localPath: String?,
    /** Thumbnail path for image attachments. */
    val thumbnailPath: String?,
    val status: AttachmentStatus = AttachmentStatus.Selected,
    val error: AttachmentError? = null,
    val progress: Float = 0f,
    /** Extracted text content. */
    val extractedText: String? = null,
    /** OCR results for image-based content. */
    val ocrText: String? = null,
    /** Number of pages/slides detected. */
    val pageCount: Int = 0,
    /** Image references found in the document. */
    val imageCount: Int = 0,
    val processedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Original image dimensions (orientation-corrected); 0 for non-image attachments. */
    val width: Int = 0,
    val height: Int = 0,
    /** Generation prompt for locally generated images; null otherwise. */
    val prompt: String? = null,
    /** Local generation model name for generated images; null otherwise. */
    val modelName: String? = null,
)

/** Result of processing a single attachment. */
data class DocumentResult(
    val attachment: DocumentAttachment,
    val status: AttachmentStatus,
    val text: String? = null,
    val ocrText: String? = null,
    val pageCount: Int = 0,
    val imageCount: Int = 0,
    val error: AttachmentError? = null,
)

/** OCR result for a single image region/page. */
data class OcrResult(
    val imageId: String,
    val text: String,
    val confidence: Float = 0f,
    val pageNumber: Int? = null,
)

/** Extracted image from a document. */
data class ExtractedImage(
    val id: String,
    val pageNumber: Int?,
    val localPath: String?,
    val thumbnailPath: String?,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
)

/** Type alias so DocumentMetadata can be used interchangeably with DocumentAttachment. */
typealias DocumentMetadata = DocumentAttachment
