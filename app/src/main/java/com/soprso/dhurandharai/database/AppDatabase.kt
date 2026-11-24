package com.soprso.dhurandharai.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Conversation::class, Message::class, ModelMetadata::class, DownloadRecord::class, DocumentAttachmentEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun modelDao(): ModelDao
    abstract fun downloadDao(): DownloadDao
    abstract fun documentAttachmentDao(): DocumentAttachmentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN catalogId TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN architecture TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN parameterCount TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN source TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN license TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN filePath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE models ADD COLUMN lastUsedAt INTEGER")
                db.execSQL("ALTER TABLE models ADD COLUMN status TEXT NOT NULL DEFAULT 'INSTALLED'")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloads (
                        catalogId TEXT NOT NULL PRIMARY KEY,
                        url TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        totalBytes INTEGER NOT NULL,
                        downloadedBytes INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        error TEXT,
                        expectedSha256 TEXT,
                        bytesPerSecond INTEGER NOT NULL,
                        startedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_conversations_updated_at` ON `conversations` (`updatedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_models_catalog_id` ON `models` (`catalogId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_models_sha256` ON `models` (`sha256`)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `document_attachments` (
                        `id` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `messageId` TEXT,
                        `fileName` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `contentUri` TEXT,
                        `localPath` TEXT,
                        `thumbnailPath` TEXT,
                        `status` TEXT NOT NULL,
                        `errorCode` TEXT,
                        `errorMessage` TEXT,
                        `progress` REAL NOT NULL,
                        `extractedText` TEXT,
                        `ocrText` TEXT,
                        `pageCount` INTEGER NOT NULL,
                        `imageCount` INTEGER NOT NULL,
                        `processedAt` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY (`id`),
                        FOREIGN KEY (`conversationId`) REFERENCES `conversations` (`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_document_attachments_conversation_id` ON `document_attachments` (`conversationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_document_attachments_message_id` ON `document_attachments` (`messageId`)",
                )
            }
        }

        /**
         * Image support on top of the attachment model: dimensions plus the
         * local generation prompt/model. Image binaries remain on disk; only
         * these small metadata columns are added.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `document_attachments` ADD COLUMN `width` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `document_attachments` ADD COLUMN `height` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `document_attachments` ADD COLUMN `prompt` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `document_attachments` ADD COLUMN `modelName` TEXT",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "offline_ai.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
