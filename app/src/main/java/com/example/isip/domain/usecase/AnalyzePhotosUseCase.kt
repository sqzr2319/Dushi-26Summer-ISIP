package com.example.isip.domain.usecase

import com.example.isip.data.PhotoRepository
import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.Photo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 分析照片用例
 * 批量分析相册中的照片
 */
class AnalyzePhotosUseCase(
    private val photoRepository: PhotoRepository
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
            val existing = photoRepository.getAnalysisResult(photo.id)
            if (existing != null) {
                completed++
                emit(AnalysisProgress(total, completed, "跳过已分析: ${photo.fileName}"))
                return@forEach
            }

            try {
                // 执行分析（简化版：基于规则的分析）
                val result = analyzePhoto(photo)

                // 保存结果
                photoRepository.saveAnalysisResult(result)
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
        val existing = photoRepository.getAnalysisResult(photoId)
        if (existing != null) return existing

        val result = analyzePhoto(photo)
        photoRepository.saveAnalysisResult(result)
        return result
    }

    /**
     * 分析新增照片（增量更新）
     */
    suspend fun analyzeNewPhotos(): Int {
        val allPhotos = photoRepository.getAllPhotos()
        val unanalyzedPhotos = photoRepository.getUnanalyzedPhotos()

        unanalyzedPhotos.forEach { photo ->
            analyzeSinglePhoto(photo.id)
        }

        return unanalyzedPhotos.size
    }

    /**
     * 简单的照片分析逻辑（基于规则）
     * TODO: 后续可以集成真实的 AI 模型
     */
    private fun analyzePhoto(photo: Photo): ImageAnalysisResult {
        val categories = mutableListOf<String>()
        val tags = mutableListOf<String>()
        var description = ""

        // 基于尺寸判断分类
        val aspectRatio = photo.width.toFloat() / photo.height
        when {
            aspectRatio > 1.5f -> {
                categories.add("风景")
                tags.add("#风景")
                description = "一张横向拍摄的照片"
            }
            aspectRatio < 0.7f -> {
                categories.add("人像")
                tags.add("#人像")
                description = "一张竖向拍摄的照片"
            }
            else -> {
                categories.add("日常")
                tags.add("#日常")
                description = "一张方形构图的照片"
            }
        }

        // 基于文件名判断
        val fileName = photo.fileName.lowercase()
        when {
            "screenshot" in fileName || "截图" in fileName -> {
                categories.add("截图")
                tags.add("#截图")
                description = "屏幕截图"
            }
            "wechat" in fileName || "微信" in fileName -> {
                categories.add("社交")
                tags.add("#微信")
            }
            "camera" in fileName || "相机" in fileName -> {
                tags.add("#相机拍摄")
            }
        }

        // 基于拍摄时间判断
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = photo.dateTaken
        }
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        tags.add("#${year}年")
        tags.add("#${month}月")

        // 基于位置信息
        if (photo.latitude != null && photo.longitude != null) {
            tags.add("#有位置信息")
            categories.add("旅行")
        }

        return ImageAnalysisResult(
            photoId = photo.id,
            categories = categories.distinct(),
            tags = tags.distinct(),
            ocrText = "", // OCR 需要集成 ML Kit 或其他服务
            description = description,
            confidence = 0.7f // 基于规则的分析，置信度设为 0.7
        )
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
