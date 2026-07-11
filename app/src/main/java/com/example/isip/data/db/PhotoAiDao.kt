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

    /** Loads the complete persisted search index in one query. */
    @Query("""
        SELECT photo_ai.*, photos.asset_id
        FROM photo_ai
        INNER JOIN photos ON photo_ai.photo_id = photos.id
    """)
    suspend fun getAllPhotoAiWithAssetIds(): List<PhotoAiWithAssetId>

    @Query("DELETE FROM photo_ai WHERE photo_id = :photoId")
    suspend fun deletePhotoAi(photoId: Long)

    @Query("DELETE FROM photo_ai")
    suspend fun deleteAllPhotoAi()
}
