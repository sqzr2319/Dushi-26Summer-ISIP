package com.example.isip.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PhotoEntity::class,
        PhotoAiEntity::class,
        ManualTagEntity::class,
        SmartAlbumEntity::class,
        AgentLogEntity::class,
        CleanupCandidateEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao
    abstract fun photoAiDao(): PhotoAiDao
    abstract fun manualTagDao(): ManualTagDao
    abstract fun smartAlbumDao(): SmartAlbumDao
    abstract fun agentLogDao(): AgentLogDao
    abstract fun cleanupCandidateDao(): CleanupCandidateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "isip_database"
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `manual_tags` (
                        `photo_id` INTEGER NOT NULL,
                        `normalized_tag` TEXT NOT NULL,
                        `display_tag` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`photo_id`, `normalized_tag`),
                        FOREIGN KEY(`photo_id`) REFERENCES `photos`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_manual_tags_photo_id` ON `manual_tags` (`photo_id`)"
                )
            }
        }
    }
}
