package com.example.isip.domain.skill

import android.util.Log
import com.example.isip.data.ai.PhotoContentAnalysis
import com.example.isip.data.ai.PhotoContentAnalyzer
import com.example.isip.data.ai.VisualLabel
import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.Photo

/**
 * 单张图片理解 Skill（李佳乔负责）。
 *
 * CLIP 只承担端侧粗分类和 embedding 建索引；OCR、描述和隐私相关标签由可选的
 * [detailAnalyzer] 完成。任一模型失败时仍返回另一模型的结果，避免批处理被单图中断。
 */
class AnalyzeImageSkill(
    private val clipAnalyzer: ClipImageAnalyzer? = null,
    private val detailAnalyzer: PhotoContentAnalyzer? = null
) : Skill<AnalyzeImageSkill.Input, ImageAnalysisResult> {

    data class Input(
        val photo: Photo,
        val requireDetail: Boolean = false,
        val detailConfidenceThreshold: Float = DEFAULT_DETAIL_THRESHOLD
    )

    data class ClipAnalysis(
        val categories: List<String>,
        val labels: List<VisualLabel> = emptyList(),
        val confidence: Float,
        /** 由实现保存向量后返回路径；Skill 本身不持有大数组。 */
        val embeddingPath: String? = null,
        val modelName: String = "mobileclip",
        val modelVersion: String = "unknown"
    )

    fun interface ClipImageAnalyzer {
        suspend fun analyze(photo: Photo): ClipAnalysis
    }

    override suspend fun execute(input: Input): ImageAnalysisResult {
        require(input.detailConfidenceThreshold in 0f..1f) {
            "detailConfidenceThreshold 必须在 0..1 之间"
        }

        val clip = try {
            clipAnalyzer?.analyze(input.photo)
        } catch (error: Exception) {
            // Keep the detailed-model fallback, but do not hide a broken semantic
            // index: without this log, image embedding failures looked like a normal
            // low-confidence CLIP result and natural-language search had no vectors.
            Log.w(TAG, "MobileCLIP embedding failed for ${input.photo.id}", error)
            null
        }
        val needsDetail = input.requireDetail || clip == null ||
            clip.categories.any { it in DETAIL_CATEGORIES } ||
            clip.confidence < input.detailConfidenceThreshold
        val detail = when {
            input.requireDetail -> requireNotNull(detailAnalyzer) {
                "精细图片分析模型未配置"
            }.analyze(input.photo)
            needsDetail -> runCatching { detailAnalyzer?.analyze(input.photo) }.getOrNull()
            else -> null
        }

        val categories = distinctTerms(detail?.categories.orEmpty() + clip?.categories.orEmpty())
            .ifEmpty { listOf(fallbackCategory(input.photo)) }
        val labelTags = (detail?.tags.orEmpty() +
            detail?.labels.orEmpty().map(VisualLabel::text) +
            clip?.labels.orEmpty().map(VisualLabel::text))
            .map { it.removePrefix("#") }
        val tags = distinctTerms(labelTags).take(MAX_TERMS).map { "#$it" }
        val confidence = listOfNotNull(detail?.confidence, clip?.confidence)
            .maxOrNull()?.coerceIn(0f, 1f) ?: FALLBACK_CONFIDENCE

        return ImageAnalysisResult(
            photoId = input.photo.id,
            categories = categories.take(MAX_TERMS),
            ocrText = detail?.ocrText.orEmpty(),
            tags = tags,
            description = detail?.description?.takeIf(String::isNotBlank)
                ?: clipDescription(categories, clip),
            confidence = confidence,
            modelName = modelName(clip, detail),
            modelVersion = modelVersion(clip, detail),
            embeddingPath = clip?.embeddingPath
        )
    }

    private fun distinctTerms(values: List<String>): List<String> = values
        .map(String::trim).filter(String::isNotEmpty).distinct()

    private fun fallbackCategory(photo: Photo): String = when {
        photo.fileName.contains("screenshot", true) || "截图" in photo.fileName -> "截图"
        photo.width > photo.height -> "横向照片"
        photo.height > photo.width -> "竖向照片"
        else -> "照片"
    }

    private fun clipDescription(categories: List<String>, clip: ClipAnalysis?): String =
        if (clip != null) "CLIP 端侧粗分类：${categories.joinToString("、")}" else "暂无视觉模型分析结果。"

    private fun modelName(clip: ClipAnalysis?, detail: PhotoContentAnalysis?): String = when {
        clip != null && detail != null -> "${clip.modelName}+${detailAnalyzer?.modelName}"
        detail != null -> detailAnalyzer?.modelName ?: "detail-model"
        clip != null -> clip.modelName
        else -> "rules-fallback"
    }

    private fun modelVersion(clip: ClipAnalysis?, detail: PhotoContentAnalysis?): String = when {
        clip != null && detail != null -> "${clip.modelVersion}+${detailAnalyzer?.modelVersion}"
        detail != null -> detailAnalyzer?.modelVersion ?: "unknown"
        clip != null -> clip.modelVersion
        else -> "1"
    }

    override fun getToolDescription(): String = """
        |## 工具名称
        |analyze_image
        |## 功能
        |分析单张照片。优先用端侧 CLIP 粗分类；低置信度、截图/文档或明确要求时再做 OCR、标签和描述精细分析。
        |## 输入参数
        |- photo_id (String, 必填): 本地照片 ID
        |- require_detail (Boolean, 可选): 是否强制精细分析
        |## 输出
        |ImageAnalysisResult：categories、ocrText、tags、description、confidence、modelName/modelVersion。
    """.trimMargin()

    companion object {
        private const val TAG = "AnalyzeImageSkill"
        const val DEFAULT_DETAIL_THRESHOLD = 0.30f
        private const val FALLBACK_CONFIDENCE = 0.2f
        private const val MAX_TERMS = 8
        private val DETAIL_CATEGORIES = setOf("截图", "文档", "票据", "证件", "银行卡")
    }
}
