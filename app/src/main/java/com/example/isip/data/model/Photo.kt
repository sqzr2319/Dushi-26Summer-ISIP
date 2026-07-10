package com.example.isip.data.model

/**
 * 照片实体类（简化版，移除 Room 注解）
 */
data class Photo(
    val id: String,                 // 照片唯一标识
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