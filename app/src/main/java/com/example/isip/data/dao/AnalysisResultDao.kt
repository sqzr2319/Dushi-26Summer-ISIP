package com.example.photoagent.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.photoagent.data.model.ImageAnalysisResult
import kotlinx.coroutines.flow.Flow

/**
 * 分析结果数据库操作接口
 */
@Dao
interface AnalysisResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: ImageAnalysisResult)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(results: List<ImageAnalysisResult>)

    @Query("SELECT * FROM analysis_results WHERE photoId = :photoId")
    suspend fun getByPhotoId(photoId: String): ImageAnalysisResult?

    @Query("SELECT * FROM analysis_results")
    suspend fun getAll(): List<ImageAnalysisResult>

    @Query("SELECT * FROM analysis_results")
    fun getAllFlow(): Flow<List<ImageAnalysisResult>>

    @Query("DELETE FROM analysis_results WHERE photoId = :photoId")
    suspend fun deleteByPhotoId(photoId: String)

    @Query("DELETE FROM analysis_results")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM analysis_results")
    suspend fun getCount(): Int

    @Query("SELECT * FROM analysis_results WHERE categories LIKE '%' || :category || '%'")
    suspend fun getByCategory(category: String): List<ImageAnalysisResult>

    @Query("SELECT * FROM analysis_results WHERE ocrText LIKE '%' || :keyword || '%'")
    suspend fun getByOcrKeyword(keyword: String): List<ImageAnalysisResult>
}