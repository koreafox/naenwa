package com.naenwa.remote.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChatSession::class, ChatMessage::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 마이그레이션: claudeSessionId 컬럼 추가
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chat_sessions ADD COLUMN claudeSessionId TEXT")
            }
        }

        // 마이그레이션: 인덱스 추가 (성능 최적화)
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // chat_sessions 테이블에 updatedAt, createdAt 인덱스 추가
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_updated ON chat_sessions(updatedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_created ON chat_sessions(createdAt)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "naenwa_chat_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
