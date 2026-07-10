package com.example.photoagent.domain.usecase

import com.example.photoagent.data.PhotoRepository
import com.example.photoagent.data.dao.AnalysisResultDao
import com.example.photoagent.data.model.ImageAnalysisResult
import com.example.photoagent.data.model.Photo
import com.example.photoagent.domain.skill.AnalyzeImageSkill
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 分析照片用例
 * 批量分析相册中的照片
 */
class AnalyzePhotosUseCase(
    private val photoRepository: PhotoRepository,
    private val analysisResultDao: AnalysisResultDao,
    private val analyzeImageSkill: AnalyzeImageSkill
) {

    /**
     * 分析所有照片
     * @return Flow<AnalysisProgress> 分析进度
     */
    fun analyzeAllPhotos(): Flow<AnalysisProgress> = flow {
        val photos = photoRepository.getAllPhotos()
        val total = photos.size
        var completed = 0

        emit(AnalysisProgress(total, completed, "开始分析..."))

        photos.forEach { photo ->
            // 检查是否已分析过
            val existing = analysisResultDao.getByPhotoId(photo.id)
            if (existing != null) {
                completed++
                emit(AnalysisProgress(total, completed, "跳过已分析: ${photo.fileName}"))
                return@forEach
            }

            try {
                val input = AnalyzeImageSkill.Input(photo.filePath)
                val result = analyzeImageSkill.execute(input)

                // 保存到数据库
                analysisResultDao.insert(result)
                completed++
                emit(AnalysisProgress(total, completed, "已完成: ${photo.fileName}"))

            } catch (e: Exception) {
                emit(AnalysisProgress(total, completed, "分析失败: ${photo.fileName} - ${e.message}"))
            }
        }

        emit(AnalysisProgress(total, completed, "分析完成!"))
    }

    /**
     * 分析单张照片
     */
    suspend fun analyzeSinglePhoto(photoId: String): ImageAnalysisResult? {
        val photo = photoRepository.getPhotoById(photoId) ?: return null

        // 检查是否已分析
        val existing = analysisResultDao.getByPhotoId(photoId)
        if (existing != null) return existing

        val input = AnalyzeImageSkill.Input(photo.filePath)
        val result = analyzeImageSkill.execute(input)
        analysisResultDao.insert(result)
        return result
    }

    /**
     * 分析新增照片（增量更新）
     */
    suspend fun analyzeNewPhotos(): Int {
        val allPhotos = photoRepository.getAllPhotos()
        var newCount = 0

        allPhotos.forEach { photo ->
            val existing = analysisResultDao.getByPhotoId(photo.id)
            if (existing == null) {
                analyzeSinglePhoto(photo.id)
                newCount++
            }
        }

        return newCount
    }
}

/**
 * 分析进度
 */
data class AnalysisProgress(
    val total: Int,
    val completed: Int,
    val message: String
) {
    fun progressPercent(): Float = if (total > 0) completed.toFloat() / total else 0f
}