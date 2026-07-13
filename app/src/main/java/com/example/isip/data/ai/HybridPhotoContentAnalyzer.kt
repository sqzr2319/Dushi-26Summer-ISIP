package com.example.isip.data.ai

import android.content.Context
import android.util.Log
import com.example.isip.data.model.Photo
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * 混合模式照片内容分析器
 *
 * 智能选择本地Gemma模型或云端Qwen API，具有自动降级和容错能力
 *
 * 策略：
 * 1. 优先使用本地Gemma模型（隐私优先、离线可用）
 * 2. 本地失败时降级到云端Qwen API（网络可用时）
 * 3. 都失败时使用基于规则的回退分析
 */
class HybridPhotoContentAnalyzer(
    context: Context,
    private val preferLocal: Boolean = true,
    private val localTimeout: Long = 10_000L, // 本地推理超时时间
    private val cloudTimeout: Long = 15_000L  // 云端API超时时间
) : PhotoContentAnalyzer {

    private val gemmaAnalyzer: GemmaPhotoContentAnalyzer by lazy {
        GemmaPhotoContentAnalyzer(context)
    }

    private val qwenAnalyzer: Qwen35PhotoContentAnalyzer by lazy {
        Qwen35PhotoContentAnalyzer(context)
    }

    override val modelName: String
        get() = currentAnalyzer?.modelName ?: "hybrid-fallback"

    override val modelVersion: String
        get() = currentAnalyzer?.modelVersion ?: "1.0"

    private var currentAnalyzer: PhotoContentAnalyzer? = null
    private var localAvailable: Boolean = true
    private var cloudAvailable: Boolean = true

    /**
     * 分析照片，智能选择推理引擎
     */
    override suspend fun analyze(photo: Photo): PhotoContentAnalysis {
        // 策略1: 优先本地
        if (preferLocal && localAvailable) {
            try {
                return analyzeWithLocal(photo).also {
                    currentAnalyzer = gemmaAnalyzer
                    Log.d(TAG, "使用本地Gemma模型完成分析")
                }
            } catch (e: Exception) {
                Log.w(TAG, "本地模型分析失败: ${e.message}")
                handleLocalFailure(e)
            }
        }

        // 策略2: 降级到云端
        if (cloudAvailable) {
            try {
                return analyzeWithCloud(photo).also {
                    currentAnalyzer = qwenAnalyzer
                    Log.d(TAG, "使用云端Qwen API完成分析")
                }
            } catch (e: Exception) {
                Log.w(TAG, "云端API分析失败: ${e.message}")
                handleCloudFailure(e)
            }
        }

        // 策略3: 回退到基于规则的分析
        Log.w(TAG, "所有推理引擎不可用，使用规则回退")
        currentAnalyzer = null
        return createFallbackAnalysis(photo)
    }

    /**
     * 使用本地Gemma模型分析
     */
    private suspend fun analyzeWithLocal(photo: Photo): PhotoContentAnalysis {
        return withTimeout(localTimeout) {
            gemmaAnalyzer.analyze(photo)
        }
    }

    /**
     * 使用云端Qwen API分析
     */
    private suspend fun analyzeWithCloud(photo: Photo): PhotoContentAnalysis {
        return withTimeout(cloudTimeout) {
            qwenAnalyzer.analyze(photo)
        }
    }

    /**
     * 处理本地推理失败
     */
    private fun handleLocalFailure(error: Exception) {
        when (error) {
            is TimeoutCancellationException -> {
                Log.e(TAG, "本地推理超时")
                // 超时不代表模型不可用，下次仍可尝试
            }
            is ModelInitializationException -> {
                Log.e(TAG, "本地模型初始化失败，禁用本地推理")
                localAvailable = false
            }
            else -> {
                Log.e(TAG, "本地推理错误: ${error.message}")
            }
        }
    }

    /**
     * 处理云端API失败
     */
    private fun handleCloudFailure(error: Exception) {
        when {
            error.message?.contains("timeout") == true -> {
                Log.e(TAG, "云端API超时")
            }
            error.message?.contains("network") == true -> {
                Log.e(TAG, "网络错误，暂时禁用云端API")
                cloudAvailable = false
            }
            else -> {
                Log.e(TAG, "云端API错误: ${error.message}")
            }
        }
    }

    /**
     * 创建回退分析结果（基于规则）
     */
    private fun createFallbackAnalysis(photo: Photo): PhotoContentAnalysis {
        val isScreenshot = photo.fileName.lowercase().let {
            "screenshot" in it || "screen_shot" in it || "截图" in it
        }

        val categories = mutableListOf<String>()
        val tags = mutableListOf<String>()

        // 基于文件名和属性的简单分类
        when {
            isScreenshot -> {
                categories.add("截图")
                tags.add("#截图")
            }
            photo.width > photo.height * 1.5 -> {
                categories.add("横向照片")
                tags.add("#风景")
            }
            photo.height > photo.width * 1.5 -> {
                categories.add("竖向照片")
                tags.add("#人物")
            }
            else -> {
                categories.add("方形照片")
                tags.add("#照片")
            }
        }

        // 添加时间标签
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = photo.dateTaken
        }
        tags.add("#${calendar.get(java.util.Calendar.YEAR)}年")
        tags.add("#${calendar.get(java.util.Calendar.MONTH) + 1}月")

        // 添加位置标签
        if (photo.latitude != null && photo.longitude != null) {
            tags.add("#有位置信息")
        }

        return PhotoContentAnalysis(
            categories = categories,
            tags = tags,
            ocrText = "",
            description = "基于图片属性的基础分类（AI模型未能运行）",
            confidence = 0.3f,
            labels = tags.map { VisualLabel(it.removePrefix("#"), 0.3f) }
        )
    }

    /**
     * 重置可用性状态（用于重试）
     */
    fun resetAvailability() {
        localAvailable = true
        cloudAvailable = true
        Log.d(TAG, "推理引擎可用性已重置")
    }

    /**
     * 获取当前状态
     */
    fun getStatus(): AnalyzerStatus {
        return AnalyzerStatus(
            localAvailable = localAvailable,
            cloudAvailable = cloudAvailable,
            currentMode = when (currentAnalyzer) {
                is GemmaPhotoContentAnalyzer -> "local-gemma"
                is Qwen35PhotoContentAnalyzer -> "cloud-qwen"
                else -> "fallback-rules"
            }
        )
    }

    /**
     * 强制使用本地模型
     */
    suspend fun forceLocal(photo: Photo): PhotoContentAnalysis {
        return analyzeWithLocal(photo)
    }

    /**
     * 强制使用云端API
     */
    suspend fun forceCloud(photo: Photo): PhotoContentAnalysis {
        return analyzeWithCloud(photo)
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            gemmaAnalyzer.release()
        } catch (e: Exception) {
            // Ignore if not initialized
        }
    }

    companion object {
        private const val TAG = "HybridAnalyzer"
    }
}

/**
 * 分析器状态
 */
data class AnalyzerStatus(
    val localAvailable: Boolean,
    val cloudAvailable: Boolean,
    val currentMode: String
) {
    fun isAnyAvailable(): Boolean = localAvailable || cloudAvailable

    fun getPreferredMode(): String = when {
        localAvailable -> "local"
        cloudAvailable -> "cloud"
        else -> "fallback"
    }
}
