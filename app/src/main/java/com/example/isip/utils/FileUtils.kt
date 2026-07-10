package com.example.photoagent.utils

import java.io.File

/**
 * 文件操作工具类
 */
object FileUtils {

    /**
     * 检查文件是否存在
     */
    fun fileExists(filePath: String): Boolean {
        return File(filePath).exists()
    }

    /**
     * 获取文件大小（MB）
     */
    fun getFileSizeMB(filePath: String): Float {
        val file = File(filePath)
        return if (file.exists()) file.length() / (1024f * 1024f) else 0f
    }

    /**
     * 获取文件扩展名
     */
    fun getFileExtension(filePath: String): String {
        return filePath.substringAfterLast('.', "")
    }

    /**
     * 生成唯一ID
     */
    fun generateId(): String {
        return java.util.UUID.randomUUID().toString()
    }

    /**
     * 判断是否为图片文件
     */
    fun isImageFile(filePath: String): Boolean {
        val extension = getFileExtension(filePath).lowercase()
        return extension in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif")
    }
}