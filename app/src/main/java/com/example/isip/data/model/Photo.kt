package com.example.photoagent.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 照片实体类
 * 对应数据库 photos 表
 */
@Entity(tableName = "photos")
data class Photo(
    @PrimaryKey
    val id: String,                 // 照片唯一标识（可用文件路径hash或UUID）
    val filePath: String,           // 文件绝对路径
    val fileName: String,           // 文件名
    val dateTaken: Long,            // 拍摄时间 (Unix timestamp)
    val dateModified: Long,         // 修改时间 (Unix timestamp)
    val latitude: Double?,          // GPS纬度
    val longitude: Double?,         // GPS经度
    val sizeBytes: Long,            // 文件大小（字节）
    val width: Int,                 // 图片宽度（像素）
    val height: Int                 // 图片高度（像素）
)