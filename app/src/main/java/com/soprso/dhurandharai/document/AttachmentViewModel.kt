package com.soprso.dhurandharai.document

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soprso.dhurandharai.database.AppDatabase
import com.soprso.dhurandharai.database.DocumentAttachmentDao
import com.soprso.dhurandharai.database.DocumentAttachmentEntity
import com.soprso.dhurandharai.document.context.ContextBuilder
import com.soprso.dhurandharai.document.context.DocumentChunk
import com.soprso.dhurandharai.document.context.DocumentChunker
import com.soprso.dhurandharai.document.image.ImageFormat
import com.soprso.dhurandharai.document.extractors.CsvExtractor
import com.soprso.dhurandharai.document.extractors.DocxExtractor
import com.soprso.dhurandharai.document.extractors.PdfExtractor
import com.soprso.dhurandharai.document.extractors.PptxExtractor
import com.soprso.dhurandharai.document.extractors.TextExtractor
import com.soprso.dhurandharai.document.vision.ImageExtractor
import com.soprso.dhurandharai.document.vision.ImagePreprocessor
import com.soprso.dhurandharai.document.vision.OcrProcessor
import com.soprso.dhurandharai.document.vision.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** ViewModel managing document attachments and processing state for a conversation. */
class AttachmentViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = getApplication<Application>()
    private val db = AppDatabase.get(app)
    private val dao: DocumentAttachmentDao = db.documentAttachmentDao()

    private val imageExtractor by lazy { ImageExtractor(app, ImagePreprocessor) }

    private val processor by lazy {
        val router = DocumentRouter(
            textExtractor = TextExtractor(app),
            csvExtractor = CsvExtractor(app),
            pdfExtractor = PdfExtractor(
                app,
                PageRenderer(app),
                OcrProcessor(app),
                imageExtractor,
            ),
            docxExtractor = DocxExtractor(app),
            pptxExtractor = PptxExtractor(app),
        )
        DocumentProcessor(
            app,
            router,
            PageRenderer(app),
            OcrProcessor(app),
            imageExtractor,
        )
    }

    private val _attachments = MutableStateFlow<Map<String, List<DocumentAttachment>>>(emptyMap())
    val attachments: StateFlow<Map<String, List<DocumentAttachment>>> = _attachments.asStateFlow()

    private val _processingJobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    val processingJobs: StateFlow<Map<String, Job>> = _processingJobs.asStateFlow()

    private val ocrProcessor = OcrProcessor(app)
    private var observeJob: Job? = null

    init {
        // Remove files left behind by an interrupted import or a deleted row.
        viewModelScope.launch(Dispatchers.IO) {
            val referenced = dao.allLocalPaths() + dao.allThumbnailPaths()
            DocumentStorage.cleanupOrphans(app, referenced.toSet())
        }
    }

    /** Returns attachments for a given conversation, observing from Room. */
    fun observeAttachments(conversationId: String) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            dao.observeForConversation(conversationId).collect { entities ->
                val map = _attachments.value
                val list = entities.map { entityToDomain(it) }
                _attachments.value = map + (conversationId to list)
            }
        }
    }

    /** Stops observing a conversation (clears its entry). */
    fun stopObserving(conversationId: String) {
        _attachments.value = _attachments.value - conversationId
    }

    /**
     * Registers one or more attachments for a message. Copies files to local storage,
     * persists metadata to Room, and starts background processing.
     *
     * Returns the generated attachment IDs so the caller can link them to a message.
     */
    suspend fun addAttachments(
        conversationId: String,
        messageId: String?,
        uris: List<Uri>,
    ): List<String> {
        val newIds = mutableListOf<String>()
        for (uri in uris) {
            val mimeType = mimeTypeForUri(uri) ?: "application/octet-stream"
            val fileName = fileNameForUri(uri) ?: "attachment"
            val att = processor.processAttachment(
                uri = uri,
                fileName = fileName,
                mimeType = mimeType,
                conversationId = conversationId,
                messageId = messageId,
            )
            newIds += att.id
            val entity = domainToEntity(att)
            dao.insert(entity)

            val map = _attachments.value
            val existing = map[conversationId] ?: emptyList()
            _attachments.value = map + (conversationId to (existing + att))
        }
        return newIds
    }

    /** Links previously added attachments to a specific message ID. */
    suspend fun linkAttachmentsToMessage(attachmentIds: List<String>, messageId: String) {
        for (id in attachmentIds) {
            val entity = dao.getById(id) ?: continue
            dao.update(entity.copy(messageId = messageId))
        }
        val firstEntity = attachmentIds.firstOrNull()?.let { dao.getById(it) } ?: return
        observeAttachments(firstEntity.conversationId)
    }

    /** Returns attachments for a specific message. */
    suspend fun getAttachmentsForMessage(messageId: String): List<DocumentAttachment> {
        return dao.getForMessage(messageId).map { entityToDomain(it) }
    }

    /** Removes an attachment and its local files. */
    fun removeAttachment(attachmentId: String, conversationId: String) {
        viewModelScope.launch {
            deleteAttachment(attachmentId)
            val map = _attachments.value
            val existing = map[conversationId] ?: emptyList()
            _attachments.value = map + (conversationId to existing.filter { it.id != attachmentId })
        }
    }

    /**
     * Deletes the metadata row and then removes its files only when no other
     * attachment row references them. Safe against a shared/duplicated path.
     */
    suspend fun deleteAttachment(attachmentId: String): DocumentAttachment? {
        val entity = dao.getById(attachmentId) ?: return null
        dao.delete(entity)

        val localPath = entity.localPath
        if (localPath != null && dao.countOtherLocalReferences(localPath, entity.id) == 0) {
            DocumentStorage.deletePath(app, localPath)
        }
        val thumbnailPath = entity.thumbnailPath
        if (thumbnailPath != null &&
            dao.countOtherThumbnailReferences(thumbnailPath, entity.id) == 0
        ) {
            DocumentStorage.deletePath(app, thumbnailPath)
        }
        return entityToDomain(entity)
    }

    /**
     * Copies a locally generated image into persistent image storage, creates
     * its 256 px WebP thumbnail, stores metadata and links it to [messageId].
     * Returns null when the output file is missing or not a readable image.
     */
    suspend fun importGeneratedImage(
        conversationId: String,
        messageId: String?,
        sourceFile: File,
        fileName: String,
        mimeType: String,
        prompt: String?,
        modelName: String?,
    ): DocumentAttachment? = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val extension = ImageFormat.extensionForMime(mimeType, fileName)

        val storedFile = try {
            DocumentStorage.copyFileToImageStorage(app, sourceFile, id, extension)
        } catch (_: Exception) {
            return@withContext null
        }

        val extracted = imageExtractor.processStoredImage(storedFile, id)
        if (extracted == null) {
            storedFile.delete()
            return@withContext null
        }

        val now = System.currentTimeMillis()
        val attachment = DocumentAttachment(
            id = id,
            conversationId = conversationId,
            messageId = messageId,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = storedFile.length(),
            contentUri = null,
            localPath = extracted.localPath,
            thumbnailPath = extracted.thumbnailPath,
            status = AttachmentStatus.Ready,
            width = extracted.width,
            height = extracted.height,
            prompt = prompt,
            modelName = modelName,
            imageCount = 1,
            pageCount = 1,
            processedAt = now,
            createdAt = now,
        )

        dao.insert(domainToEntity(attachment))
        val map = _attachments.value
        val existing = map[conversationId] ?: emptyList()
        _attachments.value = map + (conversationId to (existing + attachment))
        attachment
    }

    /** Copies the original image bytes to a user-selected SAF destination. */
    suspend fun saveImageToUri(attachment: DocumentAttachment, destination: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val source = DocumentStorage.resolvePath(app, attachment.localPath)
                ?: return@withContext false
            if (!source.isFile) return@withContext false
            try {
                app.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } != null
            } catch (_: Exception) {
                false
            }
        }

    /** Builds AI-ready context text from all attachments in a conversation. */
    fun buildContextForConversation(conversationId: String): String {
        val list = _attachments.value[conversationId] ?: return ""
        return buildContextFromAttachments(list)
    }

    /** Builds AI-ready context text from a specific list of attachments (e.g. one message). */
    fun buildContextFromAttachments(attachments: List<DocumentAttachment>): String {
        val readyAttachments = attachments.filter { it.status == AttachmentStatus.Ready }
        if (readyAttachments.isEmpty()) return ""

        val chunksByDoc = mutableMapOf<String, MutableList<DocumentChunk>>()
        for (att in readyAttachments) {
            var allChunks = mutableListOf<DocumentChunk>()
            val text = att.extractedText
            if (!text.isNullOrBlank()) {
                allChunks += DocumentChunker.chunk(
                    documentId = att.id,
                    fileName = att.fileName,
                    text = text.orEmpty(),
                    pageNumber = null,
                )
            }
            val ocr = att.ocrText
            if (!ocr.isNullOrBlank()) {
                allChunks += DocumentChunker.chunk(
                    documentId = att.id,
                    fileName = att.fileName,
                    text = ocr.orEmpty(),
                    isOcr = true,
                )
            }
            if (allChunks.isNotEmpty()) {
                val existing = chunksByDoc[att.id] ?: mutableListOf()
                existing.addAll(allChunks)
                chunksByDoc[att.id] = existing
            }
        }

        return ContextBuilder.buildContext(chunksByDoc.toMap())
    }

    /** Returns whether OCR is available on this device. */
    val ocrAvailable: Boolean get() = ocrProcessor.isAvailable

    /** Cancels all in-flight processing jobs. */
    fun cancelAll() {
        for (job in _processingJobs.value.values) {
            job.cancel()
        }
        _processingJobs.value = emptyMap()
    }

    private fun mimeTypeForUri(uri: Uri): String? {
        // Providers usually expose the real type; fall back to the display name.
        val declared = runCatching { app.contentResolver.getType(uri) }.getOrNull()
        if (!declared.isNullOrBlank()) return declared

        return ImageFormat.mimeTypeForFileName(fileNameForUri(uri) ?: uri.lastPathSegment)
            ?: when (fileNameForUri(uri)?.substringAfterLast('.', "")?.lowercase()) {
                "pdf" -> "application/pdf"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "txt", "md" -> "text/plain"
                "csv" -> "text/csv"
                else -> "application/octet-stream"
            }
    }

    private fun fileNameForUri(uri: Uri): String? {
        return try {
            app.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: null
        } catch (e: Exception) {
            null
        }
    }

    private fun entityToDomain(entity: DocumentAttachmentEntity): DocumentAttachment {
        return DocumentAttachment(
            id = entity.id,
            conversationId = entity.conversationId,
            messageId = entity.messageId,
            fileName = entity.fileName,
            mimeType = entity.mimeType,
            sizeBytes = entity.sizeBytes,
            contentUri = entity.contentUri,
            localPath = entity.localPath,
            thumbnailPath = entity.thumbnailPath,
            status = AttachmentStatus.valueOf(entity.status),
            error = if (entity.errorCode != null) {
                AttachmentError(entity.errorCode, entity.errorMessage ?: "", true)
            } else null,
            progress = entity.progress,
            extractedText = entity.extractedText,
            ocrText = entity.ocrText,
            pageCount = entity.pageCount,
            imageCount = entity.imageCount,
            processedAt = entity.processedAt,
            createdAt = entity.createdAt,
            width = entity.width,
            height = entity.height,
            prompt = entity.prompt,
            modelName = entity.modelName,
        )
    }

    private fun domainToEntity(att: DocumentAttachment): DocumentAttachmentEntity {
        return DocumentAttachmentEntity(
            id = att.id,
            conversationId = att.conversationId,
            messageId = att.messageId,
            fileName = att.fileName,
            mimeType = att.mimeType,
            sizeBytes = att.sizeBytes,
            contentUri = att.contentUri,
            localPath = att.localPath,
            thumbnailPath = att.thumbnailPath,
            status = att.status.name,
            errorCode = att.error?.code,
            errorMessage = att.error?.message,
            progress = att.progress,
            extractedText = att.extractedText,
            ocrText = att.ocrText,
            pageCount = att.pageCount,
            imageCount = att.imageCount,
            processedAt = att.processedAt,
            createdAt = att.createdAt,
            width = att.width,
            height = att.height,
            prompt = att.prompt,
            modelName = att.modelName,
        )
    }
}
