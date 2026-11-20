package com.soprso.dhurandharai.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Conversation::class, Message::class, ModelMetadata::class, DownloadRecord::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun modelDao(): ModelDao
    abstract fun downloadDao(): DownloadDao

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
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
