package com.soprso.dhurandharai.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persisted metadata for a document attachment linked to a conversation/message. */
@Entity(
    tableName = "document_attachments",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversationId"], name = "idx_document_attachments_conversation_id"),
        Index(value = ["messageId"], name = "idx_document_attachments_message_id"),
    ],
)
data class DocumentAttachmentEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val messageId: String?,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentUri: String?,
    val localPath: String?,
    val thumbnailPath: String?,
    val status: String,
    val errorCode: String?,
    val errorMessage: String?,
    val progress: Float,
    val extractedText: String?,
    val ocrText: String?,
    val pageCount: Int,
    val imageCount: Int,
    val processedAt: Long,
    val createdAt: Long,
    /**
     * Image-only metadata. Dimensions are the decoded, orientation-corrected
     * original dimensions. Prompt/modelName are set for locally generated
     * images and remain null for images picked from device storage.
     */
    val width: Int = 0,
    val height: Int = 0,
    val prompt: String? = null,
    val modelName: String? = null,
)
