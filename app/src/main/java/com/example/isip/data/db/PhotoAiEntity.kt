package com.example.isip.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photo_ai",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["photo_id"], unique = true)]
)
data class PhotoAiEntity(
    @PrimaryKey
    @ColumnInfo(name = "photo_id")
    val photoId: Long,
    @ColumnInfo(name = "short_caption")
    val shortCaption: String? = null,
    @ColumnInfo(name = "categories_json")
    val categoriesJson: String? = null,
    @ColumnInfo(name = "tags_json")
    val tagsJson: String? = null,
    @ColumnInfo(name = "objects_json")
    val objectsJson: String? = null,
    @ColumnInfo(name = "scene")
    val scene: String? = null,
    @ColumnInfo(name = "ocr_text")
    val ocrText: String? = null,
    @ColumnInfo(name = "embedding_path")
    val embeddingPath: String? = null,
    @ColumnInfo(name = "model_name")
    val modelName: String? = null,
    @ColumnInfo(name = "model_version")
    val modelVersion: String? = null,
    @ColumnInfo(name = "processed_at")
    val processedAt: Long? = null
)
