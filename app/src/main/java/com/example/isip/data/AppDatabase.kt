package com.example.photoagent.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import android.content.Context
import com.example.photoagent.data.dao.AnalysisResultDao
import com.example.photoagent.data.model.ImageAnalysisResult
import com.example.photoagent.data.model.Photo
import com.example.photoagent.utils.Converters

/**
 * 本地数据库
 */
@Database(
    entities = [Photo::class, ImageAnalysisResult::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun analysisResultDao(): AnalysisResultDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "photo_agent_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}