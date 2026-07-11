package com.example.isip.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cleanup_candidates")
data class CleanupCandidateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "group_id")
    val groupId: String,
    @ColumnInfo(name = "media_type")
    val mediaType: String,
    @ColumnInfo(name = "reason")
    val reason: String,
    @ColumnInfo(name = "keep_photo_id")
    val keepPhotoId: Long? = null,
    @ColumnInfo(name = "candidate_photo_ids")
    val candidatePhotoIds: String,
    @ColumnInfo(name = "total_size")
    val totalSize: Long? = null,
    @ColumnInfo(name = "confidence")
    val confidence: Float? = null,
    @ColumnInfo(name = "status")
    val status: String = "pending",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
