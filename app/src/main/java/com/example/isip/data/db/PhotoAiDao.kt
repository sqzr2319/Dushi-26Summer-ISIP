package com.example.isip.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PhotoAiDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhotoAi(photoAi: PhotoAiEntity)

    @Query("SELECT * FROM photo_ai WHERE photo_id = :photoId")
    suspend fun getPhotoAiByPhotoId(photoId: Long): PhotoAiEntity?

    @Query("SELECT * FROM photo_ai")
    suspend fun getAllPhotoAi(): List<PhotoAiEntity>

    @Query("DELETE FROM photo_ai WHERE photo_id = :photoId")
    suspend fun deletePhotoAi(photoId: Long)

    @Query("DELETE FROM photo_ai")
    suspend fun deleteAllPhotoAi()
}
