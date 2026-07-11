package com.example.isip.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        PhotoEntity::class,
        PhotoAiEntity::class,
        SmartAlbumEntity::class,
        AgentLogEntity::class,
        CleanupCandidateEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao
    abstract fun photoAiDao(): PhotoAiDao
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
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
