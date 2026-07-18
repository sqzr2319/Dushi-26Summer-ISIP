package com.example.isip.data.ai

import android.content.Context
import android.util.Log
import com.example.isip.data.model.Photo
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * App-level photo analyzer. All AI analysis is performed by the on-device
 * Gemma 4 model; Qwen and network fallbacks are intentionally not used.
 */
class HybridPhotoContentAnalyzer(
    context: Context,
    private val localTimeout: Long = 60_000L
) : PhotoContentAnalyzer {

    private val gemmaAnalyzer = GemmaPhotoContentAnalyzer(context)
    private var localAvailable = true
    private var usedGemma = false

    override val modelName: String
        get() = if (usedGemma) gemmaAnalyzer.modelName else "gemma-4-fallback"

    override val modelVersion: String
        get() = if (usedGemma) gemmaAnalyzer.modelVersion else "1.0"

    override suspend fun analyze(photo: Photo): PhotoContentAnalysis {
        if (!localAvailable) return createFallbackAnalysis(photo)

        return try {
            withTimeout(localTimeout) { gemmaAnalyzer.analyze(photo) }.also {
                usedGemma = true
                Log.d(TAG, "使用本地 Gemma 4 模型完成分析")
            }
        } catch (error: Exception) {
            usedGemma = false
            when (error) {
                is ModelInitializationException -> localAvailable = false
                is TimeoutCancellationException -> Log.e(TAG, "Gemma 本地推理超时", error)
                else -> Log.e(TAG, "Gemma 本地推理失败", error)
            }
            createFallbackAnalysis(photo)
        }
    }

    fun resetAvailability() {
        localAvailable = true
        usedGemma = false
    }

    fun getStatus(): AnalyzerStatus = AnalyzerStatus(
        localAvailable = localAvailable,
        cloudAvailable = false,
        currentMode = if (usedGemma) "local-gemma-4" else "fallback-rules"
    )

    suspend fun forceLocal(photo: Photo): PhotoContentAnalysis =
        withTimeout(localTimeout) { gemmaAnalyzer.analyze(photo) }

    fun release() = gemmaAnalyzer.release()

    private fun createFallbackAnalysis(photo: Photo): PhotoContentAnalysis {
        val isScreenshot = photo.fileName.lowercase().let {
            "screenshot" in it || "screen_shot" in it || "截图" in it
        }
        val category = when {
            isScreenshot -> "截图"
            photo.width > photo.height * 1.5 -> "横向照片"
            photo.height > photo.width * 1.5 -> "竖向照片"
            else -> "照片"
        }
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = photo.dateTaken }
        val tags = buildList {
            add("#$category")
            add("#${calendar.get(java.util.Calendar.YEAR)}年")
            add("#${calendar.get(java.util.Calendar.MONTH) + 1}月")
            if (photo.latitude != null && photo.longitude != null) add("#有位置信息")
        }
        return PhotoContentAnalysis(
            categories = listOf(category),
            tags = tags,
            description = "基础分类结果（Gemma 模型未能运行）",
            confidence = 0.3f,
            labels = tags.map { VisualLabel(it.removePrefix("#"), 0.3f) }
        )
    }

    private companion object {
        const val TAG = "GemmaPhotoAnalyzer"
    }
}

data class AnalyzerStatus(
    val localAvailable: Boolean,
    val cloudAvailable: Boolean,
    val currentMode: String
) {
    fun isAnyAvailable(): Boolean = localAvailable
    fun getPreferredMode(): String = if (localAvailable) "local-gemma-4" else "fallback"
}
