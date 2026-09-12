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
 * - Qwen3.5-4B-Q4_K_M.gguf (主模型)
 * - mmproj-F16.gguf (多模态投影层)
 *
 * 模型需预先部署到 /data/data/com.example.isip/files/models/
 * 参见 tools/setup_models.ps1
 */
class QwenInferenceEngine private constructor(
    private val context: Context,
    /**
     * 生效的配置，对调用方只读可见。
     *
     * 暴露出来是为了让 [getInstance] 能检测"第二个调用方传了不同的 config"这种
     * 静默失效 —— 见那里的注释。
     */
    val config: ModelConfig
) {
    private var llamaWrapper: LlamaCppWrapper? = null
    private var isInitialized = false
    private var modelPath: String? = null
    private var mmProjPath: String? = null

    /**
     * 送入视觉塔前，图片最长边的像素上限。
     *
     * 初值取 [ModelConfig.imageSide]（生产默认 512）。这里保留成 `var` 是为了让
     * 诊断覆盖能在不重编译的情况下做分辨率对照 —— 见 [ModelConfig.imageSide]
     * 里那张速度/质量对照表。
     */
    @Volatile
    var maxImageSidePx: Int = config.imageSide

    /**
     * 最近一次送入视觉塔前图片的尺寸链路，格式
     * `输入位图 -> maxSize 参数 -> 实际送出 (字节数)`。
     *
     * 供调用方写进基准日志：这台设备不把应用自身 logcat 交给 adb，
     * 没有这个字段就无法确认分辨率覆盖到底有没有生效。
     */
    @Volatile
    var lastSentImageDims: String = ""

    /**
     * 最近一次模型返回的**原始文本**（未解析）。
     *
     * 为什么必须留：设备不把应用 logcat 交给 adb，`Log.d` 打出来的解析中间态在这台
     * 机器上等于没有。而"字段有没有被模型生成、有没有被解析到"只能看原文 ——
     * 例如精简 schema 后 `confidence` 一直落到默认值 0.5，是模型没生成它，
     * 还是解析没匹配上，不看原文无法区分。
     */
    @Volatile
    var lastRawResponse: String = ""

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
        prompt: String = DEFAULT_ANALYSIS_PROMPT,
        /**
         * true 时把 [prompt] 当作**完整**提示词直接送入模型，不再追加 JSON schema。
         *
         * 默认 false 保持生产行为：调用方给场景指令，这里补上输出格式要求。但这也意味着
         * 提示词会被追加成"场景指令 + 6 字段 JSON schema"（实测约 190 token），对
         * Qwen3.5-2B 偏重。做提示词复杂度对照实验时需要能关掉这层包装，否则传进去的
         * 短提示词照样会被撑大，实验结论无效。
         */
        promptIsComplete: Boolean = false,
        /** 仅诊断用：覆盖本模型的 maxTokens（<=0 表示不覆盖）。 */
        maxTokensOverride: Int = -1,
        /** 仅诊断用：覆盖送入视觉塔前的最长边上限（<=0 表示用 [maxImageSidePx]）。 */
        maxImageSideOverride: Int = -1,
        /** 仅诊断用：覆盖采样温度（<0 表示用 config.temperature）。 */
        temperatureOverride: Float = -1f
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

            // Bitmap -> ByteArray (JPEG)，并按最长边限制尺寸。
            //
            // 1024 而不是早前的 384。llama.cpp 在加载 Qwen-VL 的 mmproj 时会明确警告：
            //
            //   load_hparams: Qwen-VL models require at minimum 1024 image tokens
            //                 to function correctly on grounding tasks
            //   load_hparams: if you encounter problems with accuracy, try adding
            //                 --image-min-tokens 1024
            //
            // 而图像 token 数约为 (最长边 / patch_size / n_merge)^2 * 比例 —— 384px 的
            // 照片只产生 84-196 个 token，远低于要求的 1024。这是**客户端人为限制**了
            // 视觉分辨率：mtmd 只会把过大的图缩小到 image_max_pixels，绝不会放大，所以
            // 在这里先缩到 384 就永久丢掉了细节。
            //
            // 1024 对应约 768-1024 个图像 token（4:3 照片 1024x768 -> 32x24 合并后 768），
            // 在 2048 的上下文里放得下。
            val maxSize = if (maxImageSideOverride > 0) maxImageSideOverride else maxImageSidePx
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
            // 质量 90：更高的分辨率下再压到 85 会引入可见块效应，而视觉塔现在真的会
            // 用上这些细节。
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            val imageBytes = outputStream.toByteArray()

            // 记录实际尺寸。这台设备上 App 自身的 logcat 拿不到，所以把关键量交给
            // 调用方（它写文件）。之前排查"改了 maxSize 但图像 token 不变"时，
            // 就是因为看不到这一级而多绕了几轮。
            lastSentImageDims = "${bitmap.width}x${bitmap.height}" +
                " -> maxSize=$maxSize -> ${scaledBitmap.width}x${scaledBitmap.height}" +
                " (${imageBytes.size / 1024} KB)"

            if (scaledBitmap != bitmap) scaledBitmap.recycle()

            Log.d(TAG, "图片已缩放: ${scaledBitmap.width}x${scaledBitmap.height}, ${imageBytes.size / 1024} KB")
            Log.d(TAG, "提示词(${prompt.length} 字符, promptIsComplete=$promptIsComplete): ${prompt.take(300)}")
            Log.i(TAG, "[dims] $lastSentImageDims")

            val startTime = System.currentTimeMillis()

            // 使用 suspendCoroutine 桥接 callback → suspend
            val response = suspendCoroutine<String> { cont ->
                wrapper.generateMultimodal(
                    imageData = imageBytes,
                    prompt = if (promptIsComplete) prompt else buildAnalysisPrompt(prompt),
                    maxTokens = if (maxTokensOverride > 0) maxTokensOverride else config.maxTokens,
                    temperature = if (temperatureOverride >= 0f) temperatureOverride else config.temperature
                ) { result ->
                    cont.resume(result)
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "收到响应长度: ${response.length} (耗时 ${elapsed / 1000}s)")
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
     * 用**已编码好的图片字节**直接推理，跳过 Bitmap 缩放与重编码。
     *
     * 仅诊断用。存在的理由：基准路径把原始 JPEG 字节交给 JNI，实测同一张真实照片能
     * 产出完全正确的描述；而 [analyzeImage] 走的是 BitmapFactory 解码 -> 缩放 ->
     * JPEG 重编码，结果是乱码。两者送出的尺寸已对齐，所以剩下的嫌疑就在这条
     * 解码/重编码链上。有了这个入口，同一张图能以"原字节"形式走相册路径。
     */
    suspend fun analyzeImageBytes(
        imageBytes: ByteArray,
        prompt: String = DEFAULT_ANALYSIS_PROMPT,
        promptIsComplete: Boolean = false,
        maxTokensOverride: Int = -1,
        maxImageSideOverride: Int = -1,
        temperatureOverride: Float = -1f
    ): PhotoContentAnalysis = withContext(Dispatchers.Default) {
        checkInitialized()
        val wrapper = llamaWrapper ?: throw InferenceException("模型未正确初始化")
        val response = suspendCoroutine<String> { cont ->
            wrapper.generateMultimodal(
                imageData = imageBytes,
                prompt = if (promptIsComplete) prompt else buildAnalysisPrompt(prompt),
                maxTokens = if (maxTokensOverride > 0) maxTokensOverride else config.maxTokens,
                temperature = if (temperatureOverride >= 0f) temperatureOverride else config.temperature
            ) { result -> cont.resume(result) }
        }
        lastSentImageDims = "bytes=${imageBytes.size} (raw, no re-encode)"
        parseAnalysisResponse(response)
    }

    // ------------------------------------------------------------------
    // 流水线：把视觉塔编码与 LLM 推理解耦
    // ------------------------------------------------------------------
    //
    // 单张分析里这两段是串行的（视觉塔 CPU 约 6 秒 + LLM NPU 约 11 秒），但用的是
    // 不同硬件与不同 ggml 上下文。连续分析多张时，可以让第 N+1 张的 [encodeImage]
    // 与第 N 张的 [generateFromEncoded] 重叠。
    //
    // ⚠️ **对单张分析没有帮助**：prefill 依赖视觉塔的输出，无法重叠。它只改善
    // "连续分析很多张"的总耗时。

    /**
     * 阶段一：只做视觉塔编码（CPU）。**不碰 llama context**，可与 [generateFromEncoded] 并行。
     *
     * 建议在 `Dispatchers.Default` 上调用。
     *
     * @param promptIsComplete true 表示 prompt 已是完整提示词，不再追加 JSON schema
     * @return 句柄（失败为 0），必须交给 [freeEncoded] 释放
     */
    suspend fun encodeImage(
        imageBytes: ByteArray,
        prompt: String = DEFAULT_ANALYSIS_PROMPT,
        promptIsComplete: Boolean = false
    ): Long = withContext(Dispatchers.Default) {
        checkInitialized()
        val wrapper = llamaWrapper ?: throw InferenceException("模型未正确初始化")
        val full = if (promptIsComplete) prompt else buildAnalysisPrompt(prompt)
        wrapper.encodeImage(imageBytes, full)
    }

    /**
     * 阶段二：用 [encodeImage] 的 embeddings 做 prefill + 生成（NPU），并解析为结果。
     *
     * **会独占 llama context**（内部清 KV cache），同一时刻只能有一个调用在跑。
     */
    suspend fun generateFromEncoded(
        handle: Long,
        maxTokensOverride: Int = -1,
        temperatureOverride: Float = -1f
    ): PhotoContentAnalysis = withContext(Dispatchers.Default) {
        checkInitialized()
        val wrapper = llamaWrapper ?: throw InferenceException("模型未正确初始化")
        if (handle == 0L) throw InferenceException("encodeImage 返回了空句柄")
        val response = wrapper.generateFromEncoded(
            handle = handle,
            maxTokens = if (maxTokensOverride > 0) maxTokensOverride else config.maxTokens,
            temperature = if (temperatureOverride >= 0f) temperatureOverride else config.temperature
        )
        parseAnalysisResponse(response)
    }

    /** 释放 [encodeImage] 返回的句柄。即使解析失败也必须调用，否则 embeddings 会泄漏。 */
    fun freeEncoded(handle: Long) {
        llamaWrapper?.freeEncoded(handle)
    }

    /**
     * 把位图缩放到 [maxImageSidePx] 并编码成 JPEG 字节。
     *
     * 从 [analyzeImage] 里提取出来，供流水线路径复用 —— 两条路径必须用**同一套**
     * 缩放与压缩参数，否则流水线与单张路径送进视觉塔的图不一样，输出差异会被误判成
     * 流水线本身的问题。
     */
    fun prepareImageBytes(bitmap: Bitmap, maxImageSideOverride: Int = -1): ByteArray {
        val maxSize = if (maxImageSideOverride > 0) maxImageSideOverride else maxImageSidePx
        val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
        val scaledBitmap = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
            )
        } else bitmap

        val outputStream = ByteArrayOutputStream()
        // 质量 90，与 analyzeImage 保持一致。
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val bytes = outputStream.toByteArray()

        lastSentImageDims = "${bitmap.width}x${bitmap.height}" +
            " -> maxSize=$maxSize -> ${scaledBitmap.width}x${scaledBitmap.height}" +
            " (${bytes.size / 1024} KB)"
        if (scaledBitmap != bitmap) scaledBitmap.recycle()
        return bytes
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
     * 构建分析 prompt（生产 prompt = 用户指令 + 这里的 JSON schema）。
     *
     * **schema 的字段数直接花钱**：它既撑大 prefill（这段有 180+ token，占总 prefill
     * 的绝大部分），又决定模型要生成多少 token（decode 速度约 12 tok/s，每 12 个
     * 多余 token 就是 1 秒）。
     *
     * 曾经让模型生成 `labels: [{"label":…,"confidence":…}]`，但**解析器根本不用它** ——
     * `parseAnalysisResponse` 里 labels 完全由 tags 派生
     * （`tags.take(5).map { VisualLabel(it.removePrefix("#"), confidence) }`）。
     * 也就是说那一整段是被要求生成、然后被丢掉的。已移除，省下 schema 里约 25 token
     * 加上模型输出里约 40 token。
     *
     * 保留的字段都对应真实消费者：
     * - `categories` / `tags` / `description` → `PhotoContentAnalysis`
     * - `confidence` → 解析器读，缺失时会退化成 0.5（与正常结果的 0.9+ 无法区分）
     * - `ocr_text` → 界面上的"复制文字"用得到；放最后，因为它最容易缺席
     */
    private fun buildAnalysisPrompt(userPrompt: String): String {
        return """
            $userPrompt

            请以 JSON 返回（tags 最多 4 个，description 不超过 40 字）：
            {"categories":["分类"],"tags":["#标签"],"description":"一句话描述","confidence":0.9,"ocr_text":"图中文字，无则留空"}
        """.trimIndent()
    }

    /**
     * 解析分析响应（从 JSON）
     */
    private fun parseAnalysisResponse(jsonResponse: String): PhotoContentAnalysis {
        lastRawResponse = jsonResponse
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
    /**
     * 取一个字符串数组字段。
     *
     * 除了正常的数组形式，还要容忍**把数组写成单个字符串**的情况：
     * 实测 Qwen3.5-0.8B 会输出 `"tags": "#二次元#动漫#直播#视频"`，
     * 而旧实现遇到非数组直接返回空 —— 整条 tags 被丢掉、回落到默认的 `#AI分析`，
     * 从结果上看和"模型没给标签"一样，光看输出文本根本分不出是模型的错还是解析的错。
     */
    private fun extractJsonArrayGson(root: com.google.gson.JsonObject, key: String): List<String> {
        val element = root.get(key) ?: return emptyList()
        if (element.isJsonArray) {
            return element.asJsonArray.mapNotNull {
                it.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString
            }
        }
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            // "#二次元#动漫#直播#视频" 或 "二次元, 动漫" 都要能拆开
            return element.asString
                .split('#', ',', '、', '|')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { "#$it" }
        }
        return emptyList()
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
     * 当 JSON 被截断时，逐行扫描提取可用字段。
     *
     * 触发条件：模型在 `maxTokens` 预算内没写完 JSON，gson 解析失败。产出因此是
     * **降级结果**，confidence 固定 0.5 以示区别 —— 正常解析的结果通常在 0.9 以上。
     *
     * 这条路径曾经把描述写成 `"这张照片展示了……风光。",` —— 带首尾引号和尾逗号，
     * 因为 `removeSurrounding("\"")` 对"只有开头有引号"的截断串不生效（它要求首尾
     * 都匹配）。用 [unquoteLoose] 取代。
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
                    val arr = trimmed.substringAfter("[", "").substringBefore("]")
                    categories.addAll(arr.split(",").map { unquoteLoose(it) }.filter { it.isNotEmpty() })
                }
                trimmed.startsWith("\"tags\"") -> {
                    val arr = trimmed.substringAfter("[", "").substringBefore("]")
                    tags.addAll(arr.split(",").map { unquoteLoose(it) }.filter { it.isNotEmpty() })
                }
                trimmed.startsWith("\"ocr_text\"") -> {
                    ocrText = unquoteLoose(trimmed.substringAfter(":", ""))
                }
                trimmed.startsWith("\"description\"") -> {
                    description = unquoteLoose(trimmed.substringAfter(":", ""))
                }
            }
        }

        Log.d(TAG, "行扫描结果（JSON 被截断，结果已降级）: categories=$categories, tags=$tags")

        return PhotoContentAnalysis(
            categories = categories.ifEmpty { listOf("照片") },
            tags = tags.map { if (it.startsWith("#")) it else "#$it" }.ifEmpty { listOf("#AI分析") },
            ocrText = ocrText,
            description = description.ifBlank { rawResponse.take(300) },
            confidence = 0.5f,
            labels = tags.take(5).map { VisualLabel(it.removePrefix("#"), 0.5f) }
        )
    }

    /**
     * 宽松地剥掉一个**可能被截断**的 JSON 字符串值的引号与尾随标点。
     *
     * 输入可能是 `"香港维多利亚港"`、`"香港维多利亚港",`、`"香港维多利亚` 中的任意一种。
     * 输出统一为不含引号/逗号的内容。这是 `removeSurrounding("\"")` 做不到的：
     * 后者要求首尾同时匹配，对截断串会原样返回，于是引号和逗号被当成正文写进结果。
     */
    private fun unquoteLoose(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("\"")) s = s.substring(1)
        // 未闭合的截断串不会有结尾引号，所以结尾只按标点裁，不要求配对
        s = s.trimEnd().trimEnd(',', '"').trimEnd()
        return s.replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\").trim()
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

        /**
         * 进程内单例。
         *
         * ⚠️ **`config` 只在首次创建时生效** —— 后面的调用方无论传什么都会被忽略。
         * 这本身是刻意的（模型只加载一次），但"静默忽略"是个陷阱：调用方会以为自己
         * 设置了 maxTokens / backend，实际跑的是别人的配置，而现象只是"输出不对"。
         *
         * 生产侧目前只有 [QwenPhotoContentAnalyzer] 一个调用点，传的是
         * `HybridPhotoContentAnalyzer.PRODUCTION_CONFIG`，所以现实中不会冲突。
         * 但一旦有人再写一个调用点（历史上 `QwenIntegrationTest` 就传过一套自造的、
         * 带着旧 `Q2_K_XL` 和 `maxTokens = 256` 的配置），冲突就会发生。
         *
         * 所以这里不装作无事发生：配置不一致时明确告警，把静默失效变成一个可见信号。
         */
        fun getInstance(context: Context, config: ModelConfig = ModelConfig()): QwenInferenceEngine {
            instance?.let { existing ->
                if (existing.config != config) {
                    Log.w(
                        TAG,
                        "QwenInferenceEngine 已存在，本次传入的 config 被忽略。" +
                            "生效的是首次创建时的配置：${existing.config}；" +
                            "本次传入：$config。" +
                            "若认为这是错的，请让所有调用方共用同一个 ModelConfig 实例" +
                            "（生产用 HybridPhotoContentAnalyzer.PRODUCTION_CONFIG）。"
                    )
                }
                return existing
            }
            return synchronized(this) {
                instance ?: QwenInferenceEngine(context.applicationContext, config).also {
                    Log.i(TAG, "QwenInferenceEngine 首次创建，生效配置：$config")
                    instance = it
                }
            }
        }
    }
}
/**
 * Which accelerator the native layer should try to use.
 *
 * llama.cpp decides the backend at load time from what was compiled in and what the
 * device exposes, so this is a preference, not a guarantee. The JNI layer logs what
 * it actually got, so a benchmark can state the real backend rather than assume it.
 */
enum class AccelerationBackend {
    /**
     * NPU 优先，失败回落 CPU。
     *
     * **注意：这条链里没有 GPU(Vulkan)，而且这是实测结论。** Vulkan 在这台
     * SM8750 上"能跑完但算错" —— 文本与多模态都产出结构完整、内容乱码的结果
     * （0 层卸载正确，4 层起全乱），详见 [GPU_VULKAN] 与 llama_jni.cpp 中
     * accelMode=0 分支的注释。把 Vulkan 留在自动链上会让生产路径静默产出乱码。
     */
    AUTO,

    /** 仅 CPU（gpu layers = 0）。基准里的基线。 */
    CPU,

    /**
     * Adreno GPU through the Vulkan backend.
     *
     * ⚠️ **当前不可用（正确性缺陷，非性能问题）。** 在 Qwen3.5-2B 上，只要卸载
     * 任意层（实测 4/25 层起）输出就变成多语言乱码；0 层时完全正确。纯文本同样
     * 乱码，说明是后端算子算错而不是多模态链路的问题。已试过的规避开关全部无效：
     * `GGML_VK_DISABLE_{FUSION,ASYNC,COOPMAT,COOPMAT2,MMVQ,GRAPH_OPTIMIZE}`、
     * `GGML_VK_FORCE_MMVQ`、flash attention 强制开/关。
     *
     * 保留这个枚举值是为了显式复现与继续排查（`--es backend GPU`），
     * 但它**不参与 [AUTO] 的自动回落**。
     */
    GPU_VULKAN,

    /**
     * Hexagon NPU through ggml-hexagon.
     *
     * 实测可用（见 `doc/npu-hexagon-inapp.md`）：文本约 1.5–1.9×、多模态约 1.2–1.35×。
     * 后端只接受 Q4_0/Q4_1/Q8_0/IQ4_NL/MXFP4/F16/F32 权重，所以主模型必须是 Q4_0
     * （当前正是），K-quant 会逐算子回落到 CPU。
     *
     * 相册分析日常走的是 [AUTO]，由 [AcceleratorPolicy] 决定是否启用 NPU ——
     * 默认尝试、连续失败自动降级。这个枚举值用于基准测试强制指定。
     */
    NPU_HEXAGON,
}

data class ModelConfig(
    val backend: AccelerationBackend = AccelerationBackend.AUTO,
    /**
     * CPU 线程数，**用于 LLM**（视觉塔见 [mmprojThreads]）。
     *
     * ⚠️ **不要把这个值提到 8。** 实测把 LLM 与视觉塔一起设成 8 线程会在 LLM prefill
     * 阶段挂死（两次复现，第二次 MemAvailable 有 4.38 GB，所以不是内存压力）：
     * 日志停在 `find_slot: non-consecutive token position ...` 与
     * `ggml_backend_sched_alloc_splits: failed to allocate graph` 之后，
     * CPU 占用 0%、进程存活、无 tombstone。
     *
     * 4 是长期稳定运行的值（数十次推理、零挂死）。视觉塔的线程数已经独立拆出去，
     * 所以这里不需要动。
     */
    val numThreads: Int = 4,
    /**
     * 最大生成 token 数。
     *
     * **512，而不是 64，也不是 256。** 这个值改过两轮，每轮的依据都记录在这里，
     * 因为它直接决定"输出可用 / 被截断成半句话"。
     *
     * - **64（已废弃）**：依据是"Qwen3.5-2B 在长预算下会退化：跑满也不出 EOG、
     *   输出多语言乱码"。该结论**已被证伪** —— 当时的乱码来自 Vulkan 后端算错
     *   （见 [AccelerationBackend.GPU_VULKAN]），不是模型在长预算下的行为。
     * - **256（已废弃）**：在 2B 上够用（实测 171/183/188/194 token 处自然 EOG），
     *   但 **4B 的描述更长**。实测 4B 连续两张在 256 处**跑满且无 EOG**，JSON 被从
     *   中间截断，解析降级后 description 变成 `"这张照片展示了……风光。",` 这种带
     *   引号尾逗号的残片，confidence 掉到 0.5。同一批的第三张 213 token 正常 EOG，
     *   输出完整 —— 差别只在预算够不够。
     *
     * 预算不是"每次都生成这么多"：模型遇到 EOG 就停（实测 2B 在 171–214、
     * 4B 在 213–253 处收尾）。所以调大只影响**本来就会被截断**的那些图，是纯粹的兜底，
     * 不会拖慢正常路径。
     *
     * 最坏代价（2B，生产模型，NPU）：512 token 解码约 51 s，加上视觉塔与 prefill
     * 约 50 s，合计约 100 s，远在 [HybridPhotoContentAnalyzer] 的 300 s 超时内。
     * （4B 同口径约 200 s，也在超时内，但日常耗时会翻倍 —— 这是它退居次选的原因。）
     */
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,           // 采样温度
    val contextSize: Int = 2048,             // 上下文窗口大小
    val quantizationType: QuantizationType = QuantizationType.Q4_0,  // GGUF 量化类型
    /**
     * 显式覆盖卸载层数，用于二分定位"从第几层开始 GPU 会挂"。
     * null 表示按 [backend] 推导。
     */
    val gpuLayers: Int? = null,
    /**
     * 是否加载 Hexagon NPU 后端模块。
     *
     * **默认关闭，这是刻意的。** 实测：模块能加载、能识别 v79、也能注册成 HTP
     * 后端，但设备侧会话创建失败（AEE_EUNABLETOLOAD 0x80000406），于是注册表里
     * 出现一个**没有任何可用设备**的 HTP 条目。llama.cpp 的调度器在枚举设备时会
     * 卡死，连纯 CPU 推理都跑不动 —— 也就是说失败的 NPU 集成会破坏核心功能。
     *
     * 会话创建失败的根因已定位：FastRPC 守护进程按文件名在 `ADSP_LIBRARY_PATH`
     * 里查找 DSP skel，而 App 设不了该变量。现已在 native 侧 setenv 解决
     * （见 llama_jni.cpp 的 nativeSetAdspLibraryPath），但仍保持 opt-in ——
     * 注册成功后如果计算仍失败，调度器卡死这个风险依然存在。
     */
    val enableHexagonNpu: Boolean = false,
    /**
     * flash attention 模式：0=自动（llama.cpp 默认）1=强制关闭 2=强制开启。
     *
     * 存在理由：Vulkan 上只要 offload 任意层（实测 4/25 层起）输出就变成乱码，
     * 0 层时完全正确。错在 Vulkan 的算子实现，而 FA 是嫌疑最大的一项 ——
     * 视觉塔那一侧早已强制关闭 FA（见 llama_jni.cpp 的 mtmd 参数）。
     * 这个开关用于把 FA 单独关掉验证它是不是元凶。
     */
    val flashAttnMode: Int = 0,
    /**
     * 视觉塔（mmproj）是否交给加速后端。默认 false（纯 CPU）。
     *
     * 实验开关，用于验证"视觉塔能否上 NPU"—— 它是单张分析里最长的一段
     * （768px 下约 34 秒 / 60 秒）。失败时的表现是挂死，所以默认不开。
     */
    val mmprojUseGpu: Boolean = false,
    /**
     * 视觉塔单独使用的 CPU 线程数。
     *
     * **默认用满设备核数（上限 8），而不是跟随 [numThreads]。** 这是实测结论：
     * 单张分析里最长的一段是视觉塔的 CPU 编码 —— 768px 下 31 秒，占总时间一半。
     * 而它与 LLM 用的是各自独立的 ggml 线程池：
     *
     * | 配置 | 视觉塔 | 单张总计 | 结果 |
     * | --- | --- | --- | --- |
     * | 两者都 4 线程（旧默认） | 31 s | 62 s | 正常 |
     * | **两者都 8 线程** | — | — | **挂死**（LLM prefill 阶段，两次复现） |
     * | **仅视觉塔 8 线程** | **17 s** | **49–51 s** | **正常** |
     *
     * 所以"8 线程不可用"只对 LLM 成立，视觉塔加线程是安全的净收益（省约 12 秒 / 20%）。
     * 上限取 8 是避免在核数很多的设备上过度订阅；下限 2 是保证低端机也有并行度。
     *
     * `<= 0` 表示跟随 [numThreads]（用于回归对照）。
     */
    val mmprojThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
    /**
     * KV cache 量化：0 = f16（默认），1 = q8_0，2 = q4_0。
     *
     * decode 约占单张分析的三分之一，每步读写整个 KV cache，对带宽敏感。
     * 量化能减小带宽与占用，但会影响精度，所以默认保持 f16，由实测决定是否开启。
     */
    val kvQuant: Int = 0,
    /**
     * 是否把 Vulkan 后端对 ggml 隐藏（默认 true）。
     *
     * ⚠️ **可疑项，正在排查。** 隐藏后日志出现一个"有 backend 但 0 设备"的注册项
     * （`register_backend: registered backend Vulkan (0 devices)`），而视觉塔编码阶段
     * 开始出现**死锁**：进程累计 CPU 时间完全不再增长、`mtmd_helper_eval_chunks`
     * 之后无任何输出、CPU 整体空闲、温度正常。把它做成开关就是为了对照验证。
     */
    val hideVulkan: Boolean = true,
    /**
     * 送入视觉塔前图片最长边的像素上限（决定图像 token 数，是单张耗时最大的杠杆）。
     *
     * **512 是实测的速度/质量折中点**（2B / NPU / 视觉塔 8 线程 / 精简 schema，
     * 每档 3 张真实相册照片）：
     *
     * | imageSide | 图像 token | 单张耗时 | 质量 |
     * | --- | --- | --- | --- |
     * | 768 | 336 | 32–47 s | 最好，从不出现地名幻觉 |
     * | **512** | **144** | **17.0 / 17.4 / 17.4 s** | **好，3/3 无幻觉** |
     * | 448 | 112 | 16.1 / 16.1 / 15.2 s | ⚠️ 第 0 张编造"维多利亚港（Vau Cheung Wan）" |
     *
     * 448 只比 512 快约 1 秒却开始出错，原因是已经进入**收益递减区**：decode 是
     * 固定成本（5–7 秒，取决于生成多少 token），继续砍分辨率只能压缩视觉塔与
     * prefill，而它们已被压到视觉塔 5 秒 / prefill 4 秒。
     *
     * 也就是说 512 之后再往下压是"用正确性换 1 秒"，不划算。
     */
    val imageSide: Int = 512,
    /**
     * 相册批量分析是否启用流水线（视觉塔与 LLM 重叠）。
     *
     * **默认关闭，这是实测结论。** 实测（4 张，同条件）：
     *
     * | 配置 | 单张（串行） | 批量流水线稳态 |
     * | --- | --- | --- |
     * | 视觉塔 8 线程 | **17-20 s** | 26.4 s/张（比串行还慢） |
     * | 视觉塔 4 线程 | 21-24 s | **13.3 s/张** |
     *
     * 原因是**两者争抢 CPU**：视觉塔是 CPU 密集的，而 LLM 虽然跑 NPU，其调度与
     * 数据搬运同样要 CPU。8 线程的视觉塔把 CPU 占满，并行时两边都变慢。
     *
     * 所以流水线要生效，必须把 [mmprojThreads] 降到 4 左右（`mmprojThreads + numThreads
     * <= 核数`），代价是**单张变慢约 20%**。对"以单张速度为主"的场景不值得开。
     *
     * 真要批量跑几千张、且不在意单张延迟时，把这两项一起改：
     * `mmprojThreads = 4` + `enableBatchPipeline = true`。
     */
    val enableBatchPipeline: Boolean = false
) {
    /**
     * Layers handed to the accelerator. llama.cpp treats any value >= the layer count
     * as "offload everything", and silently keeps them on the CPU when no accelerator
     * was registered — which is exactly how the previous `useGPU = true` silently did
     * nothing.
     *
     * NPU 走 999（全量下放）而不是 0：早前给 NPU 配 0 是个错误，那等于一层都不
     * 放上去，后端虽然注册了却完全不被使用。
     */
    val gpuLayerCount: Int
        get() = gpuLayers ?: when (backend) {
            AccelerationBackend.CPU -> 0
            else -> 999
        }

    /**
     * 传给原生层的加速模式。
     *
     * 原生侧会据此**显式限定可用设备**。这一步不能省：llama.cpp 的调度器会把所有
     * 可见加速设备都纳入张量分配（与 gpuLayerCount 无关），于是"CPU 模式"下 Vulkan
     * 仍会参与，实测让纯 CPU 推理从 12.05 掉到 5.54 tok/s、多模态从 13.4s 涨到 23.8s。
     *
     * 0=自动(NPU>CPU) 1=仅 CPU 2=Vulkan+CPU 3=Hexagon+CPU，与 llama_jni.cpp 的 switch 对应。
     * 自动链**不含 Vulkan** —— 它会算错，见 [AccelerationBackend.GPU_VULKAN]。
     */
    val nativeAccelMode: Int
        get() = when (backend) {
            AccelerationBackend.CPU -> 1
            AccelerationBackend.GPU_VULKAN -> 2
            AccelerationBackend.NPU_HEXAGON -> 3
            AccelerationBackend.AUTO -> 0
        }
}

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
