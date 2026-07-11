package com.example.isip.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SmartAlbumDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSmartAlbum(album: SmartAlbumEntity): Long

    @Query("SELECT * FROM smart_albums ORDER BY created_at DESC")
    suspend fun getAllSmartAlbums(): List<SmartAlbumEntity>

    @Query("DELETE FROM smart_albums WHERE id = :id")
    suspend fun deleteSmartAlbum(id: Long)
}
