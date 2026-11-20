package com.soprso.dhurandharai.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local data model. GGUF files live on disk (app-private storage) and are
 * never stored in the database — only their metadata.
 */

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["updatedAt"], name = "idx_conversations_updated_at")],
)
data class Conversation(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Model id used for this conversation's last generation; nullable. */
    val modelId: String? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class Message(
    @PrimaryKey val id: String,
    val conversationId: String,
    /** "user" or "assistant" */
    val role: String,
    val content: String,
    val createdAt: Long,
)

@Entity(
    tableName = "models",
    indices = [
        Index(value = ["catalogId"], name = "idx_models_catalog_id"),
        Index(value = ["sha256"], name = "idx_models_sha256"),
    ],
)
data class ModelMetadata(
    @PrimaryKey val id: String,
    val displayName: String,
    /** File name inside the app-private models directory. */
    val fileName: String,
    val sizeBytes: Long,
    /** e.g. "Q4_K_M"; null when not verified from the file/catalogue. */
    val quantization: String? = null,
    /** Context length; null when not verified. */
    val contextLength: Int? = null,
    /** SHA-256 of the complete file, lowercase hex. */
    val sha256: String? = null,
    /** Remote source (HTTPS), used for resume. */
    val sourceUrl: String? = null,
    val downloadedAt: Long? = null,

    val catalogId: String? = null,
    val architecture: String? = null,
    val parameterCount: String? = null,
    val source: String? = null,
    val license: String? = null,
    val filePath: String = "",
    val lastUsedAt: Long? = null,
    /** INSTALLED or ERROR; active files remain INSTALLED. */
    val status: String = "INSTALLED",
)

/** Durable state for a resumable catalogue download. */
@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey val catalogId: String,
    val url: String,
    val fileName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    /** QUEUED, DOWNLOADING, PAUSED, VERIFYING or ERROR. */
    val state: String,
    val error: String? = null,
    val expectedSha256: String? = null,
    val bytesPerSecond: Long = 0L,
    val startedAt: Long,
    val updatedAt: Long,
)
