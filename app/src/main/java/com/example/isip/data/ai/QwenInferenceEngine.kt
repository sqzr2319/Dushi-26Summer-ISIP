package com.example.isip.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Qwen3.5 本地推理引擎（llama.cpp + GGUF 实现）
 *
 * 使用 llama.cpp 引擎加载 GGUF 格式的量化模型，端侧运行。
 * 支持纯文本和多模态（图片 + 文本）推理。
 *
 * 模型要求：
 * - Qwen3.5-2B_Q4_K_M.gguf (主模型)
 * - Qwen3.5-2B.mmproj-f16.gguf (多模态投影层)
 *
 * 模型需预先部署到 /data/data/com.example.isip/files/models/
 * 参见 tools/setup_models.ps1
 * 模型要求：
 * - gemma-4-E2B_q4_0-it.gguf (主模型)
 * - gemma-4-E2B-it-mmproj.gguf (多模态投影层)
 */
class QwenInferenceEngine private constructor(
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
            Log.i(TAG, "正在初始化 Qwen3.5 模型...")
            Log.i(TAG, "主模型: $modelPath")
            if (mmProjPath != null) {
                Log.i(TAG, "多模态投影层: $mmProjPath")
            }

            // 验证模型文件存在
            val modelFile = File(modelPath)
            require(modelFile.isFile) { "模型文件不存在: $modelPath" }
            val mainModelSize = modelFile.length() / (1024 * 1024 * 1024f)
            Log.i(TAG, "主模型大小: ${String.format("%.2f", mainModelSize)} GB")

            if (mmProjPath != null) {
                val mmProjFile = File(mmProjPath)
                require(mmProjFile.isFile) { "mmproj 文件不存在: $mmProjPath" }
                val mmProjSize = mmProjFile.length() / (1024 * 1024f)
                Log.i(TAG, "投影层大小: ${String.format("%.2f", mmProjSize)} MB")
            }

            this@QwenInferenceEngine.modelPath = modelPath
            this@QwenInferenceEngine.mmProjPath = mmProjPath

            // 使用 llama.cpp wrapper 加载模型（含 mmproj）
            llamaWrapper = LlamaCppWrapper(context, config)
            val success = llamaWrapper?.loadModel(modelPath, mmProjPath) ?: false
            if (!success) {
                throw ModelInitializationException("模型加载失败")
            }

            isInitialized = true
            Log.i(TAG, "✅ Qwen3.5 模型初始化成功")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 模型初始化失败", e)
            throw ModelInitializationException("无法初始化模型: ${e.message}", e)
        }
    }

    /**
     * 分析图像并返回结构化结果
     *
     * 使用 Qwen3.5 的多模态功能处理图像
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        prompt: String = DEFAULT_ANALYSIS_PROMPT
    ): PhotoContentAnalysis = withContext(Dispatchers.Default) {
        checkInitialized()

        val wrapper = llamaWrapper ?: throw InferenceException("模型未正确初始化")

        // 即使没有 mmproj，也尝试纯文本分析
        if (mmProjPath == null) {
            Log.w(TAG, "⚠️ 未加载多模态投影层，使用纯文本分析")
            val textResult = wrapper.generate(prompt, config.maxTokens, config.temperature) { }
            val fallbackJson = """{"categories":["照片"],"tags":["#AI分析"],"description":"$textResult"}"""
            return@withContext parseAnalysisResponse(fallbackJson)
        }

        try {
            Log.d(TAG, "分析图像: ${bitmap.width}x${bitmap.height}")

            // Bitmap → ByteArray (JPEG, 缩放至 640px 防止 OOM)
            val maxSize = 640
            val scale = minOf(
                maxSize.toFloat() / bitmap.width,
                maxSize.toFloat() / bitmap.height
            )
            val scaledBitmap = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else bitmap

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val imageBytes = outputStream.toByteArray()
            if (scaledBitmap != bitmap) scaledBitmap.recycle()

            Log.d(TAG, "图片已缩放: ${scaledBitmap.width}x${scaledBitmap.height}, ${imageBytes.size / 1024} KB")

            // 使用 suspendCoroutine 桥接 callback → suspend
            val response = suspendCoroutine<String> { cont ->
                wrapper.generateMultimodal(
                    imageData = imageBytes,
                    prompt = buildAnalysisPrompt(prompt),
                    maxTokens = config.maxTokens,
                    temperature = config.temperature
                ) { result ->
                    cont.resume(result)
                }
            }

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
     * 纯文本推理（带聊天模板）
     */
    suspend fun generateText(
        prompt: String,
        maxTokens: Int = config.maxTokens
    ): String = withContext(Dispatchers.Default) {
        checkInitialized()

        val wrapper = llamaWrapper ?: throw InferenceException("模型未正确初始化")

        try {
            Log.d(TAG, "生成文本，提示词长度: ${prompt.length}")

            // 使用 suspendCoroutine 桥接 callback → suspend
            suspendCoroutine<String> { cont ->
                wrapper.generate(
                    prompt = prompt,
                    maxTokens = maxTokens,
                    temperature = config.temperature
                ) { result ->
                    cont.resume(result)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "文本生成失败", e)
            throw InferenceException("推理失败: ${e.message}", e)
        }
    }

    /**
     * 构建分析 prompt
     */
    private fun buildAnalysisPrompt(userPrompt: String): String {
        return """
            $userPrompt

            请以 JSON 格式返回分析结果：
            {
              "categories": ["分类1", "分类2"],
              "tags": ["#标签1", "#标签2"],
              "description": "照片简短描述",
              "confidence": 0.85,
              "labels": [
                {"label": "标签名", "confidence": 0.9}
              ],
              "ocr_text": "图片中识别到的文字"
            }
            注意：ocr_text 放在最后，前面的字段必须完整。
        """.trimIndent()
    }

    /**
     * 解析分析响应（从 JSON）
     */
    private fun parseAnalysisResponse(jsonResponse: String): PhotoContentAnalysis {
        return try {
            Log.d(TAG, "开始解析 JSON，长度: ${jsonResponse.length}")

            // 尝试提取 JSON（可能包含在 markdown 代码块中）
            var cleanJson = jsonResponse.trimStart()
            // 去掉开头的 ```json 或 ``` 标记
            if (cleanJson.startsWith("```")) {
                val newlinePos = cleanJson.indexOf('\n')
                cleanJson = if (newlinePos > 0) cleanJson.substring(newlinePos + 1) else ""
            }
            val jsonStart = cleanJson.indexOf("{")
            val jsonEnd = cleanJson.lastIndexOf("}")
            val actualJson = if (jsonStart >= 0 && jsonEnd > jsonStart) {
                cleanJson.substring(jsonStart, jsonEnd + 1)
            } else {
                jsonResponse
            }

            Log.d(TAG, "提取的 JSON: ${actualJson.take(300)}")

            val root = try {
                com.google.gson.JsonParser.parseString(actualJson).asJsonObject
            } catch (e: com.google.gson.JsonSyntaxException) {
                Log.w(TAG, "JSON 解析失败（可能被截断），改用行扫描提取字段")
                // 被截断时逐个字段扫描提取
                return extractFieldsLineByLine(actualJson, jsonResponse)
            }

            val categories = extractJsonArrayGson(root, "categories")
                .ifEmpty { listOf("照片") }

            val tags = extractJsonArrayGson(root, "tags")
                .map { if (it.startsWith("#")) it else "#$it" }
                .ifEmpty { listOf("#AI分析") }

            val ocrText = extractJsonStringGson(root, "ocr_text") ?: ""

            val description = extractJsonStringGson(root, "description")
                ?: actualJson.take(200)

            val confidence = extractJsonFloatGson(root, "confidence") ?: 0.5f

            Log.d(TAG, "解析结果: categories=$categories, tags=${tags.take(5)}...")
            Log.d(TAG, "描述: ${description.take(100)}")

            PhotoContentAnalysis(
                categories = categories,
                tags = tags,
                ocrText = ocrText,
                description = description,
                confidence = confidence,
                labels = tags.take(5).map { VisualLabel(it.removePrefix("#"), confidence) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "JSON 解析失败: ${e.message}", e)
            Log.e(TAG, "原始响应: $jsonResponse")

            PhotoContentAnalysis(
                categories = listOf("照片"),
                tags = listOf("#AI生成"),
                ocrText = "",
                description = jsonResponse.take(500),
                confidence = 0.7f,
                labels = listOf(VisualLabel("AI生成", 0.7f))
            )
        }
    }

    // Gson 提取函数
    private fun extractJsonArrayGson(root: com.google.gson.JsonObject, key: String): List<String> {
        val element = root.get(key) ?: return emptyList()
        if (!element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull {
            it.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString
        }
    }

    private fun extractJsonStringGson(root: com.google.gson.JsonObject, key: String): String? {
        val element = root.get(key) ?: return null
        return element.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString
    }

    private fun extractJsonFloatGson(root: com.google.gson.JsonObject, key: String): Float? {
        val element = root.get(key) ?: return null
        return element.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asFloat
    }

    /**
     * 当 JSON 被截断时，逐行扫描提取可用字段
     */
    private fun extractFieldsLineByLine(
        partialJson: String,
        rawResponse: String
    ): PhotoContentAnalysis {
        val categories = mutableListOf<String>()
        val tags = mutableListOf<String>()
        var ocrText = ""
        var description = ""

        for (line in partialJson.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("\"categories\"") -> {
                    val arr = trimmed.substringAfter("[").substringBefore("]")
                    categories.addAll(arr.split(",").map {
                        it.trim().removeSurrounding("\"").removeSurrounding("\"")
                    }.filter { it.isNotEmpty() })
                }
                trimmed.startsWith("\"tags\"") -> {
                    val arr = trimmed.substringAfter("[").substringBefore("]")
                    tags.addAll(arr.split(",").map {
                        it.trim().removeSurrounding("\"").removeSurrounding("\"")
                    }.filter { it.isNotEmpty() })
                }
                trimmed.startsWith("\"ocr_text\"") -> {
                    ocrText = trimmed.substringAfter(":").trim()
                        .removeSurrounding("\"").removeSurrounding("\"")
                        .substringBeforeLast("\"") // 处理截断
                }
                trimmed.startsWith("\"description\"") -> {
                    description = trimmed.substringAfter(":").trim()
                        .removeSurrounding("\"").removeSurrounding("\"")
                }
            }
        }

        Log.d(TAG, "行扫描结果: categories=$categories, tags=$tags")

        return PhotoContentAnalysis(
            categories = categories.ifEmpty { listOf("照片") },
            tags = tags.map { if (it.startsWith("#")) it else "#$it" }.ifEmpty { listOf("#AI分析") },
            ocrText = ocrText,
            description = description.ifBlank { rawResponse.take(300) },
            confidence = 0.5f,
            labels = tags.take(5).map { VisualLabel(it.removePrefix("#"), 0.5f) }
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
            llamaWrapper?.unload()
            llamaWrapper = null
            isInitialized = false
            Log.d(TAG, "模型资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放资源时出错", e)
        }
    }

    companion object {
        private const val TAG = "QwenInferenceEngine"

        private val DEFAULT_ANALYSIS_PROMPT = """
            分析这张照片，识别其中的内容、场景、物体和文字。
        """.trimIndent()

        @Volatile
        private var instance: QwenInferenceEngine? = null

        fun getInstance(context: Context, config: ModelConfig = ModelConfig()): QwenInferenceEngine {
            return instance ?: synchronized(this) {
                instance ?: QwenInferenceEngine(context.applicationContext, config).also {
                    instance = it
                }
            }
        }
    }
}
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
