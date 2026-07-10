package com.example.isip.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 相册数据仓库（重构版：使用内存存储 + MediaStore）
 * 负责读取手机本地相册照片和管理分析结果
 */
class PhotoRepository(
    private val context: Context
) {

    // 使用内存存储代替数据库
    private val analysisResults = MutableStateFlow<Map<String, ImageAnalysisResult>>(emptyMap())

    /**
     * 获取所有照片列表（从 MediaStore 读取）
     */
    suspend fun getAllPhotos(): List<Photo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<Photo>()
        val projection = buildProjection()
        val sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC"

        val cursor: Cursor? = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val pathColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateTakenColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateModifiedColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val latitudeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.LATITUDE)
            val longitudeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.LONGITUDE)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn).toString()
                val filePath = it.getString(pathColumn)
                val fileName = it.getString(nameColumn) ?: "unknown"
                val dateTaken = it.getLong(dateTakenColumn)
                val dateModified = it.getLong(dateModifiedColumn) * 1000
                val latitude = it.getDouble(latitudeColumn).takeIf { d -> !d.isNaN() }
                val longitude = it.getDouble(longitudeColumn).takeIf { d -> !d.isNaN() }
                val size = it.getLong(sizeColumn)
                val width = it.getInt(widthColumn)
                val height = it.getInt(heightColumn)

                photos.add(
                    Photo(
                        id = id,
                        filePath = filePath,
                        fileName = fileName,
                        dateTaken = dateTaken,
                        dateModified = dateModified,
                        latitude = latitude,
                        longitude = longitude,
                        sizeBytes = size,
                        width = width,
                        height = height
                    )
                )
            }
        }

        return@withContext photos
    }

    /**
     * 获取照片数量
     */
    suspend fun getPhotoCount(): Int = withContext(Dispatchers.IO) {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val cursor: Cursor? = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )
        cursor?.use {
            return@withContext it.count
        }
        return@withContext 0
    }

    /**
     * 获取最近N张照片
     */
    suspend fun getRecentPhotos(limit: Int): List<Photo> = withContext(Dispatchers.IO) {
        val all = getAllPhotos()
        return@withContext all.take(limit)
    }

    /**
     * 根据ID获取单张照片
     */
    suspend fun getPhotoById(photoId: String): Photo? = withContext(Dispatchers.IO) {
        val all = getAllPhotos()
        return@withContext all.find { it.id == photoId }
    }

    /**
     * 获取照片的Uri
     */
    fun getPhotoUri(photoId: String): Uri {
        return ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            photoId.toLong()
        )
    }

    /**
     * 保存分析结果（内存存储）
     */
    suspend fun saveAnalysisResult(result: ImageAnalysisResult) {
        analysisResults.value = analysisResults.value + (result.photoId to result)
    }

    /**
     * 获取分析结果
     */
    suspend fun getAnalysisResult(photoId: String): ImageAnalysisResult? {
        return analysisResults.value[photoId]
    }

    /**
     * 获取所有已分析的照片
     */
    fun getAnalyzedPhotos(): Flow<List<Photo>> {
        return analysisResults.map { results ->
            getAllPhotos().filter { photo ->
                results.containsKey(photo.id)
            }
        }
    }

    /**
     * 获取未分析的照片
     */
    suspend fun getUnanalyzedPhotos(): List<Photo> {
        val allPhotos = getAllPhotos()
        val analyzedIds = analysisResults.value.keys
        return allPhotos.filter { it.id !in analyzedIds }
    }

    /**
     * 根据分类获取照片
     */
    suspend fun getPhotosByCategory(category: String): List<Photo> {
        val allPhotos = getAllPhotos()
        return allPhotos.filter { photo ->
            val result = analysisResults.value[photo.id]
            result?.categories?.contains(category) == true
        }
    }

    /**
     * 根据标签搜索照片
     */
    suspend fun searchPhotosByTags(tags: List<String>): List<Photo> {
        val allPhotos = getAllPhotos()
        return allPhotos.filter { photo ->
            val result = analysisResults.value[photo.id]
            result?.tags?.any { tag -> tags.contains(tag) } == true
        }
    }

    /**
     * 根据 OCR 文本搜索照片
     */
    suspend fun searchPhotosByText(query: String): List<Photo> {
        val allPhotos = getAllPhotos()
        return allPhotos.filter { photo ->
            val result = analysisResults.value[photo.id]
            result?.ocrText?.contains(query, ignoreCase = true) == true ||
            result?.description?.contains(query, ignoreCase = true) == true
        }
    }

    /**
     * 清空所有分析结果
     */
    suspend fun clearAllAnalysis() {
        analysisResults.value = emptyMap()
    }

    /**
     * 构建查询列（兼容不同Android版本）
     */
    private fun buildProjection(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.LATITUDE,
                MediaStore.Images.Media.LONGITUDE,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )
        } else {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.LATITUDE,
                MediaStore.Images.Media.LONGITUDE,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: PhotoRepository? = null

        fun getInstance(context: Context): PhotoRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhotoRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}