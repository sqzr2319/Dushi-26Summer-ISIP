package com.example.isip.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ManualTagDao {
    /** 同一照片上的同一规范化标签只保存一次。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertManualTags(tags: List<ManualTagEntity>)

    @Query("SELECT * FROM manual_tags WHERE photo_id = :photoId ORDER BY created_at ASC")
    suspend fun getManualTagsByPhotoId(photoId: Long): List<ManualTagEntity>

    @Query("""
        SELECT manual_tags.*, photos.asset_id
        FROM manual_tags
        INNER JOIN photos ON manual_tags.photo_id = photos.id
        ORDER BY manual_tags.created_at ASC
    """)
    suspend fun getAllManualTagsWithAssetIds(): List<ManualTagWithAssetId>

    @Query("DELETE FROM manual_tags WHERE photo_id = :photoId AND normalized_tag = :normalizedTag")
    suspend fun deleteManualTag(photoId: Long, normalizedTag: String)
}
