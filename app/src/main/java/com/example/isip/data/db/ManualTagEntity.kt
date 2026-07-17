package com.example.isip.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 用户手动添加的标签，独立于模型产出的 photo_ai 记录。 */
@Entity(
    tableName = "manual_tags",
    primaryKeys = ["photo_id", "normalized_tag"],
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["photo_id"])]
)
data class ManualTagEntity(
    @ColumnInfo(name = "photo_id")
    val photoId: Long,
    @ColumnInfo(name = "normalized_tag")
    val normalizedTag: String,
    @ColumnInfo(name = "display_tag")
    val displayTag: String,
    @ColumnInfo(name = "source")
    val source: String = "user",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
