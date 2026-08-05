package com.droid

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.droid.storage.ContactDao
import com.droid.storage.MessageDao

@Database(
    entities = [MessageEntity::class, Contact::class],
    version = 4,                                         // ✅ Incremented to 4
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // Migration 1 → 2 (already exists)
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN type INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN voiceDuration INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN voiceFilePath TEXT NOT NULL DEFAULT ''")
            }
        }

        // Migration 2 → 3 (creates the contacts table)
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS contacts (
                        pubkey TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        lastSeen INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // ✅ NEW Migration 3 → 4: make messageId nullable and add unique index
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Convert empty strings to NULL (since column will become nullable)
                db.execSQL("UPDATE messages SET messageId = NULL WHERE messageId = ''")
                // 2. Create unique index on messageId (NULLs are ignored by SQLite UNIQUE constraint)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_messages_messageId ON messages(messageId)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bharatchat.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { instance = it }
            }
        }
    }
}