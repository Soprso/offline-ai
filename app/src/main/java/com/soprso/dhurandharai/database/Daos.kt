package com.soprso.dhurandharai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: Conversation)

    @Update
    suspend fun update(conversation: Conversation)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchUpdatedAt(id: String, updatedAt: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeForConversation(conversationId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun getForConversation(conversationId: String): List<Message>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY displayName ASC")
    fun observeAll(): Flow<List<ModelMetadata>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getById(id: String): ModelMetadata?

    @Query("SELECT * FROM models WHERE fileName = :fileName")
    suspend fun getByFileName(fileName: String): ModelMetadata?

    @Query("SELECT * FROM models WHERE catalogId = :catalogId LIMIT 1")
    suspend fun findByCatalogId(catalogId: String): ModelMetadata?

    @Query("SELECT * FROM models WHERE sha256 = :sha256 AND sha256 IS NOT NULL AND sha256 != '' LIMIT 1")
    suspend fun findBySha256(sha256: String): ModelMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelMetadata)

    @Query("UPDATE models SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: String, timestamp: Long)

    @Query("UPDATE models SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadRecord>

    @Query("SELECT * FROM downloads WHERE catalogId = :catalogId")
    suspend fun get(catalogId: String): DownloadRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: DownloadRecord)

    @Query("DELETE FROM downloads WHERE catalogId = :catalogId")
    suspend fun delete(catalogId: String)
}
