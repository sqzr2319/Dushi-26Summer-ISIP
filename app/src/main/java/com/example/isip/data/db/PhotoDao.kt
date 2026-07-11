package com.example.isip.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface PhotoDao {
    /**
     * 新媒体只插入一次；已有媒体的元数据由 [updatePhotos] 原地更新。
     *
     * 不能在这里使用 REPLACE：SQLite 的 REPLACE 会先删除旧的 photos 行，
     * 从而触发 photo_ai 外键的级联删除。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPhotosIgnoringExisting(photos: List<PhotoEntity>)

    @Update
    suspend fun updatePhotos(photos: List<PhotoEntity>)

    @Query("SELECT * FROM photos WHERE asset_id IN (:assetIds)")
    suspend fun getPhotosByAssetIds(assetIds: List<String>): List<PhotoEntity>

    /**
     * 按 asset_id 同步 MediaStore 扫描结果，同时保留已有行的主键。
     *
     * SQLite 对 IN 参数数量有限制，所以将扫描结果分批处理。每批操作位于同一
     * Room 事务中，避免插入和更新之间留下不一致的数据。
     */
    @Transaction
    suspend fun upsertScannedPhotos(photos: List<PhotoEntity>) {
        photos.chunked(MAX_ASSET_IDS_PER_BATCH).forEach { batch ->
            insertPhotosIgnoringExisting(batch)

            val existingIds = getPhotosByAssetIds(batch.map { it.assetId })
                .associate { it.assetId to it.id }
            updatePhotos(batch.map { photo ->
                photo.copy(id = checkNotNull(existingIds[photo.assetId]))
            })
        }
    }

    @Query("SELECT * FROM photos ORDER BY created_at DESC")
    suspend fun getAllPhotos(): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE id = :photoId")
    suspend fun getPhotoById(photoId: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE asset_id = :assetId")
    suspend fun getPhotoByAssetId(assetId: String): PhotoEntity?

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun getPhotoCount(): Int

    @Query("DELETE FROM photos WHERE id = :photoId")
    suspend fun deletePhoto(photoId: Long)

    @Query("DELETE FROM photos")
    suspend fun deleteAllPhotos()

    companion object {
        // Android SQLite's usual bound-parameter limit is 999; leave headroom.
        private const val MAX_ASSET_IDS_PER_BATCH = 900
    }
}
