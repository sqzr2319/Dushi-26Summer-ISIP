package com.example.isip.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gemma 4 E2B 推理引擎（llama.cpp + GGUF 实现）
 *
 * 使用 llama.cpp 引擎加载 GGUF 格式的量化模型
 * 支持多模态输入（文本 + 图像）
 *
 * 模型要求：
 * - gemma-4-E2B_q4_0-it.gguf (主模型)
 * - gemma-4-E2B-it-mmproj.gguf (多模态投影层)
 */
class GemmaInferenceEngine private constructor(
    private val context: Context,
    private val config: ModelConfig
) {
    private var llamaWrapper: LlamaCppWrapper? = null
    private var isInitialized = false
    private var modelPath: String? = null
    private var mmProjPath: String? = null

    /**
     * 初始化模型
     *
     * @param modelPath GGUF 主模型路径（assets 相对路径或绝对路径）
     * @param mmProjPath 多模态投影层路径（可选，图像分析需要）
     */
    suspend fun initialize(
        modelPath: String,
        mmProjPath: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "正在初始化 Gemma GGUF 模型...")
            Log.i(TAG, "主模型: $modelPath")
            if (mmProjPath != null) {
                Log.i(TAG, "多模态投影层: $mmProjPath")
            }

            // 从 assets 复制模型文件到内部存储
            val actualModelPath = copyModelFromAssets(modelPath)
            val actualMmProjPath = mmProjPath?.let { copyModelFromAssets(it) }

            // 验证模型文件大小
            val modelFile = File(actualModelPath)
            val mainModelSize = modelFile.length() / (1024 * 1024 * 1024f)
            Log.i(TAG, "主模型大小: ${String.format("%.2f", mainModelSize)} GB")

            if (actualMmProjPath != null) {
                val mmProjFile = File(actualMmProjPath)
                val mmProjSize = mmProjFile.length() / (1024 * 1024f)
                Log.i(TAG, "投影层大小: ${String.format("%.2f", mmProjSize)} MB")
            }

            this@GemmaInferenceEngine.modelPath = actualModelPath
            this@GemmaInferenceEngine.mmProjPath = actualMmProjPath

            // 使用 llama.cpp wrapper 加载模型
            llamaWrapper = LlamaCppWrapper(context, config)
            llamaWrapper?.initialize(actualModelPath, actualMmProjPath)

            isInitialized = true
            Log.i(TAG, "✅ Gemma 模型初始化成功")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 模型初始化失败", e)
            throw ModelInitializationException("无法初始化 Gemma 模型: ${e.message}", e)
        }
    }

    /**
     * 从 assets 复制模型文件到内部存储
     * 如果文件已存在且大小正确，则跳过复制
     */
    private fun copyModelFromAssets(assetPath: String): String {
        val suppliedFile = File(assetPath)
        if (suppliedFile.isAbsolute && suppliedFile.isFile) {
            Log.d(TAG, "使用显式模型路径: ${suppliedFile.absolutePath}")
            return suppliedFile.absolutePath
        }

        val fileName = assetPath.substringAfterLast("/")
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val destFile = File(modelsDir, fileName)

        // The deployment script puts large model files here. This check avoids
        // trying to open a non-existent 4 GB asset on every analysis.
        if (destFile.isFile && destFile.length() > 0L) {
            Log.d(TAG, "使用已部署模型: ${destFile.absolutePath}")
            return destFile.absolutePath
        }

        // 复制文件
        Log.i(TAG, "正在复制模型文件从 assets: $assetPath")
        try {
            context.assets.open(assetPath).use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    val startTime = System.currentTimeMillis()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead

                        // 每 100MB 打印进度
                        if (totalBytes % (100 * 1024 * 1024) == 0L) {
                            val sizeMB = totalBytes / (1024 * 1024)
                            Log.d(TAG, "已复制: ${sizeMB} MB")
                        }
                    }

                    val duration = (System.currentTimeMillis() - startTime) / 1000f
                    val sizeMB = totalBytes / (1024 * 1024f)
                    Log.i(TAG, "✅ 复制完成: ${String.format("%.2f", sizeMB)} MB, 耗时: ${String.format("%.1f", duration)}s")
                }
            }

            return destFile.absolutePath

        } catch (e: Exception) {
            throw ModelInitializationException(
                "未找到模型 $fileName。请先用 tools/prepare_gemma4_model.py 将其部署到 ${destFile.absolutePath}",
                e
            )
        }
    }

    /**
     * 分析图像并返回结构化结果
     *
     * 使用 llama.cpp 的多模态功能处理图像
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        prompt: String = DEFAULT_ANALYSIS_PROMPT
    ): PhotoContentAnalysis = withContext(Dispatchers.Default) {
        checkInitialized()

        if (mmProjPath == null) {
            Log.w(TAG, "⚠️ 未加载多模态投影层，无法进行图像分析")
            return@withContext createFallbackAnalysis(bitmap)
        }

        try {
            Log.d(TAG, "分析图像: ${bitmap.width}x${bitmap.height}")

            // 使用 llama.cpp 进行分析
            val response = llamaWrapper?.generateWithImage(
                bitmap = bitmap,
                prompt = buildMultimodalPrompt(prompt),
                maxTokens = config.maxTokens,
                temperature = config.temperature
            ) ?: throw InferenceException("模型未正确初始化")

            Log.d(TAG, "收到响应长度: ${response.length}")
            Log.d(TAG, "响应前200字符: ${response.take(200)}")

            // 解析 JSON 响应
            val result = parseAnalysisResponse(response)

            Log.i(TAG, "✅ 图像分析完成")
            Log.d(TAG, "解析后分类: ${result.categories}")
            Log.d(TAG, "解析后描述: ${result.description.take(100)}")

            result

        } catch (e: Exception) {
            Log.e(TAG, "图像分析失败", e)
            throw InferenceException("推理失败: ${e.message}", e)
        }
    }

    /**
     * 纯文本推理
     */
    suspend fun generateText(
        prompt: String,
        maxTokens: Int = config.maxTokens
    ): String = withContext(Dispatchers.Default) {
        checkInitialized()

        try {
            Log.d(TAG, "生成文本，提示词长度: ${prompt.length}")

            llamaWrapper?.generate(
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = config.temperature
            ) ?: throw InferenceException("模型未正确初始化")

        } catch (e: Exception) {
            Log.e(TAG, "文本生成失败", e)
            throw InferenceException("推理失败: ${e.message}", e)
        }
    }

    /**
     * 构建多模态提示词
     */
    private fun buildMultimodalPrompt(userPrompt: String): String {
        return """
            <|image|>

            $userPrompt

            请以 JSON 格式返回分析结果：
            {
              "categories": ["分类1", "分类2"],
              "tags": ["#标签1", "#标签2", "#标签3"],
              "ocr_text": "识别到的文字",
              "description": "照片描述",
              "confidence": 0.85,
              "labels": [
                {"label": "标签名", "confidence": 0.9}
              ]
            }
        """.trimIndent()
    }

    /**
     * 解析分析响应（从 JSON）
     */
    private fun parseAnalysisResponse(jsonResponse: String): PhotoContentAnalysis {
        return try {
            Log.d(TAG, "开始解析 JSON，长度: ${jsonResponse.length}")

            // 尝试提取 JSON（可能包含在其他文本中）
            val jsonStart = jsonResponse.indexOf("{")
            val jsonEnd = jsonResponse.lastIndexOf("}")

            val actualJson = if (jsonStart >= 0 && jsonEnd > jsonStart) {
                jsonResponse.substring(jsonStart, jsonEnd + 1)
            } else {
                jsonResponse
            }

            Log.d(TAG, "提取的 JSON: ${actualJson.take(300)}")

            val categories = extractJsonArray(actualJson, "categories")
            val tags = extractJsonArray(actualJson, "tags")
            val ocrText = extractJsonString(actualJson, "ocr_text") ?: ""
            val description = extractJsonString(actualJson, "description") ?: ""
            val confidence = extractJsonFloat(actualJson, "confidence") ?: 0.5f

            Log.d(TAG, "解析结果: categories=$categories, tags=$tags")
            Log.d(TAG, "描述: ${description.take(100)}")

            PhotoContentAnalysis(
                categories = categories.ifEmpty { listOf("照片") },
                tags = tags.ifEmpty { listOf("#AI分析") },
                ocrText = ocrText,
                description = description.ifEmpty { actualJson.take(200) }, // 使用生成的文本
                confidence = confidence,
                labels = tags.take(5).map { VisualLabel(it.removePrefix("#"), confidence) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "JSON 解析失败: ${e.message}", e)
            Log.e(TAG, "原始响应: $jsonResponse")

            // 解析失败时，使用生成的文本作为描述
            PhotoContentAnalysis(
                categories = listOf("照片"),
                tags = listOf("#AI生成"),
                ocrText = "",
                description = jsonResponse.take(500), // 使用前500字符
                confidence = 0.7f,
                labels = listOf(VisualLabel("AI生成", 0.7f))
            )
        }
    }

    // 简单的 JSON 提取函数（临时使用）
    private fun extractJsonArray(json: String, key: String): List<String> {
        val regex = """"$key"\s*:\s*\[(.*?)\]""".toRegex()
        val match = regex.find(json) ?: return emptyList()
        return match.groupValues[1]
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"(.*?)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonFloat(json: String, key: String): Float? {
        val regex = """"$key"\s*:\s*([0-9.]+)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    /**
     * 模拟分析（临时占位）
     */
    private fun simulateAnalysis(bitmap: Bitmap): String {
        val isLandscape = bitmap.width > bitmap.height
        return """
        {
          "categories": ["照片"],
          "tags": ["#示例标签", "#${if (isLandscape) "横向" else "竖向"}"],
          "ocr_text": "",
          "description": "Gemma GGUF 模型占位实现 - 等待 llama.cpp 集成",
          "confidence": 0.5,
          "labels": [
            {"label": "占位", "confidence": 0.5}
          ]
        }
        """.trimIndent()
    }

    /**
     * 创建回退分析
     */
    private fun createFallbackAnalysis(bitmap: Bitmap): PhotoContentAnalysis {
        return PhotoContentAnalysis(
            categories = listOf("照片"),
            tags = listOf("#示例标签"),
            ocrText = "",
            description = "未加载多模态投影层，无法分析图像",
            confidence = 0.3f,
            labels = listOf(VisualLabel("占位", 0.3f))
        )
    }

    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("模型未初始化，请先调用 initialize()")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            llamaWrapper?.release()
            llamaWrapper = null
            isInitialized = false
            Log.d(TAG, "模型资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放资源时出错", e)
        }
    }

    companion object {
        private const val TAG = "GemmaInferenceEngine"

        private val DEFAULT_ANALYSIS_PROMPT = """
            分析这张照片，识别其中的内容、场景、物体和文字。
        """.trimIndent()

        @Volatile
        private var instance: GemmaInferenceEngine? = null

        fun getInstance(context: Context, config: ModelConfig = ModelConfig()): GemmaInferenceEngine {
            return instance ?: synchronized(this) {
                instance ?: GemmaInferenceEngine(context.applicationContext, config).also {
                    instance = it
                }
            }
        }
    }
}

/**
 * 模型配置（针对 llama.cpp）
 */
data class ModelConfig(
    val useGPU: Boolean = true,              // 使用 GPU 加速（如果可用）
    val numThreads: Int = 4,                 // CPU 线程数
    val maxTokens: Int = 512,                // 最大生成 token 数
    val temperature: Float = 0.7f,           // 采样温度
    val contextSize: Int = 2048,             // 上下文窗口大小
    val quantizationType: QuantizationType = QuantizationType.Q4_0  // GGUF 量化类型
)

enum class QuantizationType {
    Q2_K,      // 2-bit 量化 (K-quant)
    Q2_K_XL,   // 2-bit 量化 (K-quant, XL)
    Q4_0,      // 4-bit 量化 (legacy)
    Q4_K_M,    // 4-bit 量化 (K-quant, medium)
    Q5_K_M,    // 5-bit 量化 (K-quant, medium)
    Q8_0,      // 8-bit 量化
    F16        // 16-bit 浮点
}

class ModelInitializationException(message: String, cause: Throwable? = null) : Exception(message, cause)
class InferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)
