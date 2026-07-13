package com.example.isip.domain.usecase

import com.example.isip.data.PhotoRepository
import com.example.isip.data.ai.PhotoContentAnalysis
import com.example.isip.data.ai.PhotoContentAnalyzer
import com.example.isip.data.ai.VisualLabel
import com.example.isip.data.ai.MobileClipEngine
import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.Photo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.example.isip.domain.skill.AnalyzeImageSkill
import java.util.Calendar

/** Generates a searchable local analysis record for each photo. */
class AnalyzePhotosUseCase(
    private val photoRepository: PhotoRepository,
    private val contentAnalyzer: PhotoContentAnalyzer,
    private val clipEngine: MobileClipEngine? = null
) {

    /**
     * Analyzes every photo that was not produced by the current model version.
     * Old rule-based rows (which have no model metadata) are automatically
     * upgraded the next time this batch operation is run.
     */
    fun analyzeAllPhotos(): Flow<AnalysisProgress> = flow {
        val photos = photoRepository.getAllPhotos()
        val existingByPhotoId = photoRepository.getAllAnalysisResults()
            .associateBy { it.photoId }
        val total = photos.size
        var completed = 0

        emit(AnalysisProgress(total, completed, "Preparing on-device analysis…"))

        photos.forEach { photo ->
            val existing = existingByPhotoId[photo.id]
            if (isCurrentModelResult(existing)) {
                completed++
                emit(AnalysisProgress(total, completed, "Skipped: ${photo.fileName}"))
                return@forEach
            }

            try {
                val result = analyzePhoto(photo)
                photoRepository.saveAnalysisResult(result)
                completed++
                emit(AnalysisProgress(total, completed, "Analyzed: ${photo.fileName}"))
            } catch (error: Exception) {
                completed++
                emit(AnalysisProgress(total, completed, "Failed: ${photo.fileName} - ${error.message}"))
            }
        }

        emit(AnalysisProgress(total, completed, "Analysis complete"))
    }

    /**
     * Analyzes one photo. Pass [force] to refresh a current model result.
     */
    suspend fun analyzeSinglePhoto(
        photoId: String,
        force: Boolean = false
    ): ImageAnalysisResult? {
        val photo = photoRepository.getPhotoById(photoId) ?: return null
        val existing = photoRepository.getAnalysisResult(photoId)
        if (!force && isCurrentModelResult(existing)) return existing

        return analyzePhoto(photo, requireDetail = force)
            .also { photoRepository.saveAnalysisResult(it) }
    }

    /** Analyzes photos that do not yet use the active visual model. */
    suspend fun analyzeNewPhotos(): Int {
        val analysesByPhotoId = photoRepository.getAllAnalysisResults()
            .associateBy { it.photoId }
        val pending = photoRepository.getAllPhotos().filter { photo ->
            !isCurrentModelResult(analysesByPhotoId[photo.id])
        }

        pending.forEach { photo ->
            analyzeSinglePhoto(photo.id)
        }
        return pending.size
    }

    private suspend fun analyzePhoto(
        photo: Photo,
        requireDetail: Boolean = false
    ): ImageAnalysisResult = AnalyzeImageSkill(clipEngine, contentAnalyzer).execute(
        AnalyzeImageSkill.Input(photo = photo, requireDetail = requireDetail)
    )

    private fun buildModelResult(
        photo: Photo,
        analysis: PhotoContentAnalysis
    ): ImageAnalysisResult {
        val readableLabels = analysis.tags.ifEmpty {
            analysis.labels.map(VisualLabel::text)
        }.map { translateLabel(it.removePrefix("#")) }
        val categories = analysis.categories.ifEmpty {
            classify(photo, analysis.labels, analysis.ocrText)
        }
        val tags = buildTags(photo, readableLabels, analysis.ocrText)
        val description = analysis.description.ifBlank {
            buildDescription(readableLabels, analysis.ocrText)
        }
        val labelConfidence = analysis.labels.take(3)
            .map(VisualLabel::confidence)
            .average()
            .toFloat()
            .takeIf { !it.isNaN() }
            ?: 0.45f

        return ImageAnalysisResult(
            photoId = photo.id,
            categories = categories,
            ocrText = analysis.ocrText,
            tags = tags,
            description = description,
            confidence = analysis.confidence.takeIf { it in 0f..1f } ?: labelConfidence,
            modelName = contentAnalyzer.modelName,
            modelVersion = contentAnalyzer.modelVersion
        )
    }

    private fun buildFallbackResult(photo: Photo): ImageAnalysisResult {
        val calendar = Calendar.getInstance().apply { timeInMillis = photo.dateTaken }
        val isScreenshot = isScreenshot(photo.fileName)
        val category = when {
            isScreenshot -> "截图"
            photo.width > photo.height -> "横向照片"
            photo.height > photo.width -> "竖向照片"
            else -> "照片"
        }

        return ImageAnalysisResult(
            photoId = photo.id,
            categories = listOf(category),
            ocrText = "",
            tags = listOf(
                "#$category",
                "#${calendar.get(Calendar.YEAR)}年",
                "#${calendar.get(Calendar.MONTH) + 1}月"
            ),
            description = "设备未能启动视觉模型，使用基础图片元数据。",
            confidence = 0.2f,
            modelName = FALLBACK_MODEL_NAME,
            modelVersion = FALLBACK_MODEL_VERSION
        )
    }

    private fun classify(
        photo: Photo,
        labels: List<VisualLabel>,
        ocrText: String
    ): List<String> = linkedSetOf<String>().apply {
        val words = labels.joinToString(" ") { it.text.lowercase() }
        if (containsAny(words, "person", "people", "human", "face", "selfie", "child", "baby")) add("人物")
        if (containsAny(words, "food", "dish", "meal", "restaurant", "cake", "drink", "coffee")) add("美食")
        if (containsAny(words, "sky", "nature", "tree", "plant", "mountain", "sea", "beach", "landscape")) add("风景")
        if (containsAny(words, "cat", "dog", "animal", "bird", "pet")) add("宠物")
        if (containsAny(words, "car", "vehicle", "train", "airplane", "road", "bicycle")) add("出行")
        if (containsAny(words, "sport", "football", "basketball", "exercise", "game")) add("运动")
        if (isScreenshot(photo.fileName)) add("截图")
        if (ocrText.isNotBlank()) add("文档")
        if (isEmpty()) add("其他")
    }.toList()

    private fun buildTags(
        photo: Photo,
        labels: List<String>,
        ocrText: String
    ): List<String> = linkedSetOf<String>().apply {
        labels.map { it.trim().removePrefix("#") }
            .filterNot { it.lowercase() in GENERIC_LABELS }
            .take(MAX_TAGS_FROM_MODEL)
            .forEach { add("#$it") }
        if (ocrText.isNotBlank()) add("#含文字")
        if (isScreenshot(photo.fileName)) add("#截图")
        if (photo.latitude != null && photo.longitude != null) add("#有位置信息")

        val calendar = Calendar.getInstance().apply { timeInMillis = photo.dateTaken }
        add("#${calendar.get(Calendar.YEAR)}年")
        add("#${calendar.get(Calendar.MONTH) + 1}月")
    }.toList()

    private fun buildDescription(labels: List<String>, ocrText: String): String {
        val visualPart = labels.take(4).joinToString("、")
        return when {
            visualPart.isNotBlank() && ocrText.isNotBlank() -> "画面包含$visualPart，并含有可识别文字。"
            visualPart.isNotBlank() -> "画面包含$visualPart。"
            ocrText.isNotBlank() -> "图片包含可识别文字。"
            else -> "未检测到足够明确的视觉内容。"
        }
    }

    private fun isCurrentModelResult(result: ImageAnalysisResult?): Boolean {
        val model = result?.modelName ?: return false
        return if (clipEngine != null) MobileClipEngine.MODEL_NAME in model
        else model == contentAnalyzer.modelName && result.modelVersion == contentAnalyzer.modelVersion
    }

    private fun isScreenshot(fileName: String): Boolean {
        val normalized = fileName.lowercase()
        return "screenshot" in normalized || "screen_shot" in normalized || "截图" in fileName
    }

    private fun containsAny(text: String, vararg candidates: String): Boolean =
        candidates.any(text::contains)

    private fun translateLabel(label: String): String = LABEL_TRANSLATIONS[label.lowercase()] ?: label

    private companion object {
        const val FALLBACK_MODEL_NAME = "rules-fallback"
        const val FALLBACK_MODEL_VERSION = "1"
        const val MAX_TAGS_FROM_MODEL = 6
        val GENERIC_LABELS = setOf("image", "photograph", "art", "font", "line")
        val LABEL_TRANSLATIONS = mapOf(
            "person" to "人物", "people" to "人群", "human" to "人物", "human face" to "人脸",
            "selfie" to "自拍", "food" to "食物", "dish" to "菜品", "meal" to "餐食",
            "drink" to "饮品", "coffee" to "咖啡", "cake" to "蛋糕", "sky" to "天空",
            "tree" to "树木", "plant" to "植物", "nature" to "自然", "mountain" to "山",
            "sea" to "海", "beach" to "海滩", "landscape" to "风景", "cat" to "猫",
            "dog" to "狗", "bird" to "鸟", "animal" to "动物", "car" to "汽车",
            "vehicle" to "车辆", "road" to "道路", "train" to "火车", "airplane" to "飞机",
            "text" to "文字", "document" to "文档", "paper" to "纸张", "poster" to "海报"
        )
    }
}

data class AnalysisProgress(
    val total: Int,
    val completed: Int,
    val message: String
) {
    fun progressPercent(): Float = if (total > 0) completed.toFloat() / total else 0f
}
