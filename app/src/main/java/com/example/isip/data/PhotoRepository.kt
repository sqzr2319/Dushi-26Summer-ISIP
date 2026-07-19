package com.example.isip.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.isip.data.db.AppDatabase
import com.example.isip.data.db.ManualTagEntity
import com.example.isip.data.db.PhotoAiEntity
import com.example.isip.data.db.PhotoEntity
import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.Photo
import com.example.isip.data.model.SmartAlbum
import com.example.isip.data.model.SmartAlbumRule
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * 相册数据仓库（使用 Room 数据库保存索引与分析结果）
 */
class PhotoRepository(
    private val context: Context
) {

    private val database = AppDatabase.getInstance(context)
    private val photoDao = database.photoDao()
    private val photoAiDao = database.photoAiDao()
    private val manualTagDao = database.manualTagDao()
    private val smartAlbumDao = database.smartAlbumDao()
    private val gson = Gson()

    /**
     * 获取所有照片列表
     */
    suspend fun getAllPhotos(): List<Photo> = withContext(Dispatchers.IO) {
        val mediaPhotos = scanMediaStorePhotos()
        photoDao.upsertScannedPhotos(mediaPhotos.map { it.toEntity() })
        return@withContext photoDao.getAllPhotos().map { it.toPhoto() }
    }

    /**
     * 获取照片数量
     */
    suspend fun getPhotoCount(): Int = withContext(Dispatchers.IO) {
        photoDao.getPhotoCount()
    }

    /**
     * 获取最近N张照片
     */
    suspend fun getRecentPhotos(limit: Int): List<Photo> = withContext(Dispatchers.IO) {
        getAllPhotos().take(limit)
    }

    /**
     * 根据ID获取单张照片
     */
    suspend fun getPhotoById(photoId: String): Photo? = withContext(Dispatchers.IO) {
        photoDao.getPhotoByAssetId(photoId)?.toPhoto()
    }

    /**
     * 获取照片的 Uri
     */
    fun getPhotoUri(photoId: String): Uri {
        return ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            photoId.toLong()
        )
    }

    /**
     * 保存分析结果
     */
    suspend fun saveAnalysisResult(result: ImageAnalysisResult) = withContext(Dispatchers.IO) {
        val photoEntity = photoDao.getPhotoByAssetId(result.photoId)
        if (photoEntity == null) {
            android.util.Log.w("PhotoRepo", "saveAnalysisResult: photo not found for assetId=${result.photoId}")
            return@withContext
        }
        android.util.Log.d("PhotoRepo", "saveAnalysisResult: saving analysis for photoId=${result.photoId}, categories=${result.categories}")
        photoAiDao.insertPhotoAi(result.toEntity(photoEntity.id))
    }

    /**
     * 获取分析结果
     */
    suspend fun getAnalysisResult(photoId: String): ImageAnalysisResult? = withContext(Dispatchers.IO) {
        val photoEntity = photoDao.getPhotoByAssetId(photoId) ?: return@withContext null
        val modelResult = photoAiDao.getPhotoAiByPhotoId(photoEntity.id)
            ?.toModel(photoEntity.assetId)
        val manualTags = manualTagDao.getManualTagsByPhotoId(photoEntity.id)
            .map { it.displayTag }

        return@withContext when {
            modelResult != null -> modelResult.withManualTags(manualTags)
            manualTags.isNotEmpty() -> ImageAnalysisResult(
                photoId = photoEntity.assetId,
                categories = emptyList(),
                ocrText = "",
                tags = manualTags,
                description = "",
                confidence = MANUAL_TAG_CONFIDENCE,
                modelName = MANUAL_TAG_MODEL,
                modelVersion = "1"
            )
            else -> null
        }
    }

    /**
     * 以两次批量 Room 查询加载模型分析和手动标签，避免搜索时重扫 MediaStore 或
     * 按照片逐条查询数据库。
     */
    suspend fun getAllAnalysisResults(): List<ImageAnalysisResult> = withContext(Dispatchers.IO) {
        val manualTagsByAssetId = manualTagDao.getAllManualTagsWithAssetIds()
            .groupBy({ it.assetId }, { it.tag.displayTag })
        val modelResultsByAssetId = photoAiDao.getAllPhotoAiWithAssetIds()
            .associate { row -> row.assetId to row.analysis.toModel(row.assetId) }

        (modelResultsByAssetId.keys + manualTagsByAssetId.keys).toSet()
            .sorted()
            .mapNotNull { assetId ->
                val manualTags = manualTagsByAssetId[assetId].orEmpty()
                val modelResult = modelResultsByAssetId[assetId]
                when {
                    modelResult != null -> modelResult.withManualTags(manualTags)
                    manualTags.isNotEmpty() -> ImageAnalysisResult(
                        photoId = assetId,
                        categories = emptyList(),
                        ocrText = "",
                        tags = manualTags,
                        description = "",
                        confidence = MANUAL_TAG_CONFIDENCE,
                        modelName = MANUAL_TAG_MODEL,
                        modelVersion = "1"
                    )
                    else -> null
                }
            }
    }

    /**
     * 保存用户手动标签。只有当前 MediaStore 索引中的照片会被写入，调用方可由
     * 返回值得知哪些 photoId 已实际更新。
     */
    suspend fun addManualTags(
        photoIds: Collection<String>,
        tags: Collection<String>,
        source: String = "user"
    ): List<String> = withContext(Dispatchers.IO) {
        val normalizedTags = tags.mapNotNull(::normalizeTag).distinctBy { it.normalized }
        if (photoIds.isEmpty() || normalizedTags.isEmpty()) return@withContext emptyList()

        val existingPhotos = photoDao.getPhotosByAssetIds(photoIds.distinct())
        manualTagDao.insertManualTags(
            existingPhotos.flatMap { photo ->
                normalizedTags.map { tag ->
                    ManualTagEntity(
                        photoId = photo.id,
                        normalizedTag = tag.normalized,
                        displayTag = tag.display,
                        source = source
                    )
                }
            }
        )
        existingPhotos.map { it.assetId }
    }

    /** 保存智能相册规则，并返回带数据库 ID 的相册。 */
    suspend fun saveSmartAlbum(album: SmartAlbum): SmartAlbum = withContext(Dispatchers.IO) {
        val id = smartAlbumDao.insertSmartAlbum(album.toEntity())
        album.copy(id = id)
    }

    suspend fun getSmartAlbums(): List<SmartAlbum> = withContext(Dispatchers.IO) {
        smartAlbumDao.getAllSmartAlbums().map { it.toModel() }
    }

    suspend fun deleteSmartAlbum(id: Long) = withContext(Dispatchers.IO) {
        smartAlbumDao.deleteSmartAlbum(id)
    }

    private data class NormalizedTag(val normalized: String, val display: String)

    private fun normalizeTag(raw: String): NormalizedTag? {
        val value = raw.trim().removePrefix("#").replace(Regex("\\s+"), " ")
        if (value.isEmpty()) return null
        return NormalizedTag(
            normalized = value.lowercase(),
            display = "#$value"
        )
    }

    private fun ImageAnalysisResult.withManualTags(manualTags: List<String>): ImageAnalysisResult = copy(
        tags = (tags + manualTags)
            .mapNotNull(::normalizeTag)
            .distinctBy { it.normalized }
            .map { it.display }
    )

    private fun SmartAlbum.toEntity() = com.example.isip.data.db.SmartAlbumEntity(
        id = id,
        name = name,
        ruleJson = gson.toJson(rule),
        createdBy = createdBy,
        createdAt = createdAt
    )

    private fun com.example.isip.data.db.SmartAlbumEntity.toModel(): SmartAlbum {
        val rule = runCatching {
            gson.fromJson(ruleJson, SmartAlbumRule::class.java)
        }.getOrDefault(SmartAlbumRule())
        return SmartAlbum(
            id = id,
            name = name,
            rule = rule,
            createdBy = createdBy,
            createdAt = createdAt
        )
    }

    /**
     * 获取所有已分析的照片
     */
    fun getAnalyzedPhotos(): Flow<List<Photo>> = flow {
        val allPhotos = getAllPhotos()
        val analyzedPhotoRowIds = photoAiDao.getAllPhotoAi().map { it.photoId }.toSet()
        emit(allPhotos.filter { photo ->
            photoDao.getPhotoByAssetId(photo.id)?.id in analyzedPhotoRowIds
        })
    }

    /**
     * 获取未分析的照片
     */
    suspend fun getUnanalyzedPhotos(): List<Photo> = withContext(Dispatchers.IO) {
        val allPhotos = getAllPhotos()
        val analyzedPhotoRowIds = photoAiDao.getAllPhotoAi().map { it.photoId }.toSet()
        return@withContext allPhotos.filter { photo ->
            photoDao.getPhotoByAssetId(photo.id)?.id !in analyzedPhotoRowIds
        }
    }

    /**
     * 根据分类获取照片
     */
    suspend fun getPhotosByCategory(category: String): List<Photo> = withContext(Dispatchers.IO) {
        val allPhotos = getAllPhotos()
        return@withContext allPhotos.filter { photo ->
            getAnalysisResult(photo.id)?.categories?.contains(category) == true
        }
    }

    /**
     * 根据标签搜索照片
     */
    suspend fun searchPhotosByTags(tags: List<String>): List<Photo> = withContext(Dispatchers.IO) {
        val allPhotos = getAllPhotos()
        return@withContext allPhotos.filter { photo ->
            val result = getAnalysisResult(photo.id)
            result?.tags?.any { tag -> tags.contains(tag) } == true
        }
    }

    /**
     * 根据 OCR 文本搜索照片
     */
    suspend fun searchPhotosByText(query: String): List<Photo> = withContext(Dispatchers.IO) {
        val allPhotos = getAllPhotos()
        return@withContext allPhotos.filter { photo ->
            val result = getAnalysisResult(photo.id)
            result?.ocrText?.contains(query, ignoreCase = true) == true ||
            result?.description?.contains(query, ignoreCase = true) == true
        }
    }

    /**
     * 清空所有分析结果
     */
    suspend fun clearAllAnalysis() = withContext(Dispatchers.IO) {
        photoAiDao.deleteAllPhotoAi()
    }

    /** Removes database rows only after MediaStore confirms that deletion succeeded. */
    suspend fun removeDeletedPhotoRecords(photoIds: Collection<String>) = withContext(Dispatchers.IO) {
        photoIds.filter(String::isNotBlank).distinct().chunked(900).forEach { batch ->
            photoDao.deletePhotosByAssetIds(batch)
        }
    }

    private suspend fun scanMediaStorePhotos(): List<Photo> = withContext(Dispatchers.IO) {
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

    private fun Photo.toEntity(): PhotoEntity {
        return PhotoEntity(
            assetId = id,
            uri = getPhotoUri(id).toString(),
            title = fileName,
            mediaType = "image",
            fileSize = sizeBytes,
            width = width,
            height = height,
            createdAt = dateTaken,
            modifiedAt = dateModified,
            contentHash = null,
            indexedAt = System.currentTimeMillis()
        )
    }

    private fun PhotoEntity.toPhoto(): Photo {
        return Photo(
            id = assetId,
            filePath = uri ?: "",
            fileName = title ?: "",
            dateTaken = createdAt ?: 0L,
            dateModified = modifiedAt ?: 0L,
            latitude = null,
            longitude = null,
            sizeBytes = fileSize ?: 0L,
            width = width ?: 0,
            height = height ?: 0
        )
    }

    private fun ImageAnalysisResult.toEntity(photoRowId: Long): PhotoAiEntity {
        return PhotoAiEntity(
            photoId = photoRowId,
            shortCaption = description,
            categoriesJson = gson.toJson(categories),
            tagsJson = gson.toJson(tags),
            objectsJson = gson.toJson(emptyList<String>()),
            scene = null,
            ocrText = ocrText,
            embeddingPath = embeddingPath,
            modelName = modelName,
            modelVersion = modelVersion,
            processedAt = analyzedAt
        )
    }

    private fun PhotoAiEntity.toModel(resultPhotoId: String): ImageAnalysisResult {
        val categories = categoriesJson?.let { gson.fromJson(it, Array<String>::class.java).toList() } ?: emptyList()
        val tags = tagsJson?.let { gson.fromJson(it, Array<String>::class.java).toList() } ?: emptyList()
        return ImageAnalysisResult(
            photoId = resultPhotoId,
            categories = categories,
            ocrText = ocrText ?: "",
            tags = tags,
            description = shortCaption ?: "",
            confidence = 0.7f,
            analyzedAt = processedAt ?: System.currentTimeMillis(),
            modelName = modelName,
            modelVersion = modelVersion,
            embeddingPath = embeddingPath
        )
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
        private const val MANUAL_TAG_CONFIDENCE = 1f
        private const val MANUAL_TAG_MODEL = "manual-tags"

        @Volatile
        private var INSTANCE: PhotoRepository? = null

        fun getInstance(context: Context): PhotoRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhotoRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
