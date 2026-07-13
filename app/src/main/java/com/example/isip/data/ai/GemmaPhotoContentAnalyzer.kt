package com.example.isip.data.ai

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import com.example.isip.data.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gemma-4-E2B-it PhotoContentAnalyzer实现
 *
 * 使用 llama.cpp + GGUF 格式的量化模型进行端侧图像分析，无需网络连接。
 */
class GemmaPhotoContentAnalyzer(
    private val context: Context,
    private val modelPath: String = DEFAULT_MODEL_PATH,
    private val mmProjPath: String = DEFAULT_MMPROJ_PATH
) : PhotoContentAnalyzer {

    private val inferenceEngine: GemmaInferenceEngine by lazy {
        GemmaInferenceEngine.getInstance(context, ModelConfig(
            useGPU = true,
            numThreads = 4,
            maxTokens = 256,
            temperature = 0.3f,
            contextSize = 2048,
            quantizationType = QuantizationType.Q4_0
        ))
    }

    private var isModelLoaded = false

    override val modelName: String = "gemma-4-e2b-it"
    override val modelVersion: String = "q4_0-gguf"

    /**
     * 分析单张照片
     */
    override suspend fun analyze(photo: Photo): PhotoContentAnalysis = withContext(Dispatchers.IO) {
        // 确保模型已加载
        ensureModelLoaded()

        try {
            // 加载图片
            val bitmap = loadPhotoBitmap(photo.id)

            // 构建分析提示词
            val prompt = buildAnalysisPrompt(photo)

            // 调用推理引擎
            val result = inferenceEngine.analyzeImage(bitmap, prompt)

            // 释放bitmap
            bitmap.recycle()

            result
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Gemma分析失败，返回基础结果", e)
            // 回退到基础分析
            createFallbackAnalysis(photo)
        }
    }

    /**
     * 确保模型已加载
     */
    private suspend fun ensureModelLoaded() {
        if (!isModelLoaded) {
            try {
                // 初始化 GGUF 模型（主模型 + 多模态投影层）
                inferenceEngine.initialize(modelPath, mmProjPath)
                isModelLoaded = true
                android.util.Log.d(TAG, "Gemma GGUF 模型加载成功")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Gemma GGUF 模型加载失败", e)
                throw ModelInitializationException("无法加载Gemma模型: ${e.message}", e)
            }
        }
    }

    /**
     * 从MediaStore加载图片
     */
    private fun loadPhotoBitmap(photoId: String): android.graphics.Bitmap {
        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            photoId.toLong()
        )

        // 使用合适的采样率加载图片
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        // 计算采样率（目标尺寸448x448）
        options.inSampleSize = calculateSampleSize(options.outWidth, options.outHeight, 448)
        options.inJustDecodeBounds = false

        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IllegalStateException("无法加载照片: $photoId")
    }

    /**
     * 计算图片采样率
     */
    private fun calculateSampleSize(width: Int, height: Int, targetSize: Int): Int {
        var sampleSize = 1
        val maxDimension = maxOf(width, height)

        while (maxDimension / (sampleSize * 2) >= targetSize) {
            sampleSize *= 2
        }

        return sampleSize
    }

    /**
     * 构建针对照片的分析提示词
     */
    private fun buildAnalysisPrompt(photo: Photo): String {
        val isScreenshot = photo.fileName.lowercase().contains("screenshot") ||
                          photo.fileName.lowercase().contains("截图")

        return when {
            isScreenshot -> """
                这是一张手机截图。请分析：
                1. 识别截图中的所有文字内容（OCR）
                2. 判断截图类型（聊天、票据、文档、社交媒体等）
                3. 提取关键信息标签
                4. 用一句话描述截图内容
            """.trimIndent()

            photo.width > photo.height * 1.5 -> """
                这是一张横向照片。请分析：
                1. 识别照片类型（风景、建筑、集体合影等）
                2. 识别主要物体和场景
                3. 如有文字则识别（OCR）
                4. 生成描述性标签
            """.trimIndent()

            else -> """
                请分析这张照片：
                1. 识别照片类型（人物、美食、物品、文档等）
                2. 识别主要内容和场景
                3. 识别照片中的文字（OCR）
                4. 生成相关标签
                5. 用一句话描述照片内容
            """.trimIndent()
        }
    }

    /**
     * 创建基础回退分析（当模型失败时）
     */
    private fun createFallbackAnalysis(photo: Photo): PhotoContentAnalysis {
        val isScreenshot = photo.fileName.lowercase().contains("screenshot") ||
                          photo.fileName.lowercase().contains("截图")

        val category = when {
            isScreenshot -> "截图"
            photo.width > photo.height -> "横向照片"
            else -> "照片"
        }

        return PhotoContentAnalysis(
            categories = listOf(category),
            tags = listOf("#$category"),
            description = "基础分类结果（模型未能运行）",
            confidence = 0.3f
        )
    }

    /**
     * 释放资源
     */
    fun release() {
        if (isModelLoaded) {
            inferenceEngine.release()
            isModelLoaded = false
        }
    }

    companion object {
        private const val TAG = "GemmaPhotoContentAnalyzer"
        private const val DEFAULT_MODEL_PATH = "models/gemma-4-E2B_q4_0-it.gguf"
        private const val DEFAULT_MMPROJ_PATH = "models/gemma-4-E2B-it-mmproj.gguf"
    }
}
