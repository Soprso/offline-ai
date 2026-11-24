package com.soprso.dhurandharai.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentAttachmentDao {

    @Query("SELECT * FROM document_attachments WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<DocumentAttachmentEntity>>

    @Query("SELECT * FROM document_attachments WHERE id = :id")
    suspend fun getById(id: String): DocumentAttachmentEntity?

    @Query("SELECT * FROM document_attachments WHERE messageId = :messageId ORDER BY createdAt ASC")
    suspend fun getForMessage(messageId: String): List<DocumentAttachmentEntity>

    @Query("SELECT * FROM document_attachments WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getForConversation(conversationId: String): List<DocumentAttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: DocumentAttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<DocumentAttachmentEntity>)

    @Update
    suspend fun update(attachment: DocumentAttachmentEntity)

    @Delete
    suspend fun delete(attachment: DocumentAttachmentEntity)

    @Query("DELETE FROM document_attachments WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query("SELECT localPath FROM document_attachments WHERE localPath IS NOT NULL")
    suspend fun allLocalPaths(): List<String>

    @Query("SELECT thumbnailPath FROM document_attachments WHERE thumbnailPath IS NOT NULL")
    suspend fun allThumbnailPaths(): List<String>

    @Query("SELECT COUNT(*) FROM document_attachments WHERE localPath = :path AND id != :excludeId")
    suspend fun countOtherLocalReferences(path: String, excludeId: String): Int

    @Query("SELECT COUNT(*) FROM document_attachments WHERE thumbnailPath = :path AND id != :excludeId")
    suspend fun countOtherThumbnailReferences(path: String, excludeId: String): Int
}
