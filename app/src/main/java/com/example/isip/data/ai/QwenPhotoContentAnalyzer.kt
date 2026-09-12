package com.example.isip.data.ai

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Log
import com.example.isip.data.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Qwen3.5 PhotoContentAnalyzer 实现。
 *
 * 使用 llama.cpp + GGUF 格式的量化模型进行端侧图像分析，无需网络连接。
 */
class QwenPhotoContentAnalyzer(
    private val context: Context,
    private val config: ModelConfig = ModelConfig(
        backend = AccelerationBackend.AUTO,
        numThreads = 4,
        // 512 而非原先的 64，也不是中间的 256。
        //
        // 64 的依据是"Qwen3.5-2B 在长生成下会退化 —— 预算 256/512 时跑满也不出
        // EOG，输出变成多语言乱码"。那个结论**已被证伪**：当时的乱码来自 Vulkan 后端
        // 算错（见 AccelerationBackend.GPU_VULKAN），不是模型在长预算下的行为。
        //
        // 之后改成 256（2B 在 171–214 token 处自然 EOG，够用）；但 4B 的描述更长，
        // 实测连续两张在 256 处**跑满且无 EOG**，JSON 被截断，解析降级成
        // `"这张照片展示了……风光。",` 这种带引号尾逗号的残片。512 对两档都安全。
        //
        // 预算只在模型本来就会被截断时才起作用 —— 遇到 EOG 它会自己停，
        // 所以调大不会拖慢正常的 2B 路径（生产模型，58–69 s/张）。
        maxTokens = 512,
        temperature = 0.3f,
        contextSize = 2048,
        // 实际加载的主模型是 Qwen3.5-2B-Q4_0（见 QwenModel.MODEL_FILE_CANDIDATES），
        // 这里必须与之一致。此前写的是 Q2_K_XL —— 那是旧回退模型，且 K-quant 在
        // Hexagon NPU 上会逐算子回落到 CPU，等于把 NPU 加速白白让掉。
        quantizationType = QuantizationType.Q4_0
    )
) : PhotoContentAnalyzer {

    private val inferenceEngine: QwenInferenceEngine by lazy {
        QwenInferenceEngine.getInstance(context, config)
    }

    private var isModelLoaded = false

    /**
     * 实际加载的主模型文件名，由 [ensureModelLoaded] 在选型后写入。
     *
     * 不直接用 `QwenModel.MODEL_NAME` —— 那个常量写死 4B，而候选链会自动回落到 2B，
     * 会把 2B 的运行结果标成 4B（此前就发生过）。
     */
    private var loadedModelFileName: String? = null

    /** 最近一次实际送出的图片尺寸链路，见 [QwenInferenceEngine.lastSentImageDims]。 */
    val lastSentImageDims: String
        get() = inferenceEngine.lastSentImageDims

    /** 最近一次模型返回的原始文本，见 [QwenInferenceEngine.lastRawResponse]。 */
    val lastRawResponse: String
        get() = inferenceEngine.lastRawResponse

    override val modelName: String
        get() = loadedModelFileName?.let { QwenModel.displayName(it) } ?: QwenModel.MODEL_NAME
    override val modelVersion: String = QwenModel.MODEL_VERSION

    /**
     * 仅诊断用：生产路径（[analyze]）下要套用的覆盖值。
     *
     * 之所以需要这两个字段：覆盖原本只作为 [analyzeWithPrompt] 的参数存在，而
     * 基准在**不传 promptOverride** 时走的是 [analyze]（也就是生产路径），
     * 参数根本传不进去 —— 实测表现为"改了 imageSide，但送出的 JPEG 尺寸完全不变
     * （512x288，hash 相同）"。诊断开关必须对生产路径同样生效，否则测的不是生产。
     */
    var maxTokensOverrideForDiagnostics: Int = -1
    var maxImageSideOverrideForDiagnostics: Int = -1

    /**
     * 仅诊断用：覆盖采样温度（<0 表示用 config 值）。
     *
     * 为什么需要：基准路径用 `temperature = 0f`（贪心），实测同一张真实照片能产出
     * 完全正确的描述；生产路径用 `config.temperature = 0.3`，同一张图却是乱码。
     * 温度是两者剩下的主要差异之一，必须能单独控制才能定因。
     */
    var temperatureOverrideForDiagnostics: Float = -1f

    /**
     * 仅诊断用：直接从该文件读图片字节，绕开 MediaStore 加载与重编码。
     *
     * 为什么需要：基准路径（直接送原始 JPEG 字节）在同一张真实照片上能产出正确描述，
     * 而相册路径（BitmapFactory 解码 + 缩放到目标尺寸 + JPEG 重编码）是乱码。两者
     * 送出的尺寸已经对齐，剩下的差异就在**这张重编码后的图**上。这个开关让同一张图
     * 能以"原字节"形式走相册路径，从而把"图片来源"与"后续代码路径"分开。
     */
    var imageFileOverrideForDiagnostics: String? = null

    /**
     * 分析单张照片
     */
    override suspend fun analyze(photo: Photo): PhotoContentAnalysis = withContext(Dispatchers.IO) {
        // 模型不可用属于"整条链路都跑不了"，必须抛出去让 Hybrid 层标记为不可用；
        // 如果在这里吞掉，界面会一直显示"基础分类结果"，看不出是模型问题。
        ensureModelLoaded()
        analyzeWithPrompt(
            photo,
            buildAnalysisPrompt(photo),
            promptIsComplete = false,
            maxTokensOverride = maxTokensOverrideForDiagnostics,
            maxImageSideOverride = maxImageSideOverrideForDiagnostics,
            temperatureOverride = temperatureOverrideForDiagnostics
        )
    }

    /**
     * 用指定提示词分析，绕开 [buildAnalysisPrompt] 的场景分支。
     *
     * 存在理由：实测生产提示词（详细中文指令 + 6 字段 JSON schema，约 190 token）会
     * 让 Qwen3.5-2B 输出退化；要判定"模型能力不足"还是"提示词过载"，必须能把两者
     * 分开测。生产路径不受影响，只有显式调用这个重载才会走覆盖。
     */
    suspend fun analyzeWithPrompt(
        photo: Photo,
        prompt: String,
        promptIsComplete: Boolean = false,
        maxTokensOverride: Int = -1,
        maxImageSideOverride: Int = -1,
        temperatureOverride: Float = -1f
    ): PhotoContentAnalysis = withContext(Dispatchers.IO) {
        ensureModelLoaded()

        try {
            // 诊断模式：直接用文件字节，跳过 MediaStore + BitmapFactory + 重编码
            val override = imageFileOverrideForDiagnostics
            if (override != null) {
                val f = java.io.File(override)
                if (f.isFile) {
                    return@withContext inferenceEngine.analyzeImageBytes(
                        f.readBytes(), prompt, promptIsComplete,
                        maxTokensOverride, maxImageSideOverride, temperatureOverride
                    )
                }
                Log.w(TAG, "imageFileOverride 不存在: $override，回退到 MediaStore")
            }
            // 解码目标跟随最终的视觉分辨率，避免在解码阶段就先丢一轮细节。
            //
            // 与 QwenInferenceEngine.maxImageSidePx（= config.imageSide）保持一致：
            // 解码阶段若小于它，后面的缩放再怎么设都恢复不了细节。
            val decodeTarget = if (maxImageSideOverride > 0) maxImageSideOverride else config.imageSide
            val bitmap = loadPhotoBitmap(photo.id, decodeTarget)
            val result = inferenceEngine.analyzeImage(
                bitmap, prompt, promptIsComplete, maxTokensOverride,
                maxImageSideOverride, temperatureOverride
            )
            bitmap.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Qwen3.5 模型分析失败，返回基础结果: ${photo.id}", e)
            createFallbackAnalysis(photo)
        }
    }

    /**
     * 流水线阶段一之前：把照片读成送进视觉塔的 JPEG 字节。
     *
     * 与 [analyzeWithPrompt] 走同一套加载与缩放（[loadPhotoBitmap] +
     * [QwenInferenceEngine.prepareImageBytes]），否则流水线与单张路径送的图不一样。
     */
    suspend fun prepareImageBytes(photo: Photo): ByteArray = withContext(Dispatchers.IO) {
        ensureModelLoaded()
        val bitmap = loadPhotoBitmap(photo.id, config.imageSide)
        val bytes = inferenceEngine.prepareImageBytes(bitmap)
        bitmap.recycle()
        bytes
    }

    /**
     * 流水线阶段一：只做视觉塔编码。
     *
     * `promptIsComplete = false` 是**必须的**：这里传的 [buildAnalysisPrompt] 只是
     * 用户指令（"请分析这张照片：1. …"），JSON schema 由引擎追加。写成 true 会让模型
     * 直接回答那几条指令、输出 `1. **照片类型**：风景…` 这样的散文，解析器拿不到 JSON，
     * 结果退化成默认的分类与标签。
     */
    suspend fun encodeImage(photo: Photo, imageBytes: ByteArray): Long =
        inferenceEngine.encodeImage(imageBytes, buildAnalysisPrompt(photo), promptIsComplete = false)

    /** 流水线阶段二：用预先编码的 embeddings 做推理并解析。 */
    suspend fun generateFromEncoded(photo: Photo, handle: Long): PhotoContentAnalysis =
        try {
            inferenceEngine.generateFromEncoded(handle)
        } catch (e: Exception) {
            Log.e(TAG, "流水线推理失败，返回基础结果: ${photo.id}", e)
            createFallbackAnalysis(photo)
        }

    /** 释放流水线句柄。 */
    fun freeEncoded(handle: Long) = inferenceEngine.freeEncoded(handle)

    /**
     * 确保模型已加载。
     *
     * 模型随 APK 以 assets/models 形式分发，llama.cpp 需要真实路径，
     * 因此首次调用时会被复制到应用私有目录（见 [QwenModel.ensureAvailable]）。
     */
    private suspend fun ensureModelLoaded() {
        if (isModelLoaded) return

        val picked = QwenModel.ensurePreferredModel(context)
            ?: throw ModelInitializationException(
                "主模型不可用：候选 ${QwenModel.MODEL_FILE_CANDIDATES} 既不在 assets/models 也不在私有目录"
            )
        val mmProjPath = QwenModel.ensureAvailable(context, QwenModel.MMPROJ_FILE_NAME)

        if (mmProjPath == null) {
            // 多模态投影层缺失只会让分析退化成"纯文本"，在相册场景里几乎等于失效，
            // 所以按初始化失败处理，让上层明确报错而不是产出无意义结果。
            throw ModelInitializationException(
                "多模态投影层不可用：缺少 assets/models/${QwenModel.MMPROJ_FILE_NAME}"
            )
        }

        // 投影层与主模型必须配套。同名不同内容是这里的真实陷阱：assets 里打包的是
        // **2B** 的 mmproj，一旦主模型选到 4B 而私有目录的 mmproj 缺失，就会拿 2B 的
        // 视觉塔去配 4B 的主模型 —— 模型照跑，只是输出无意义。宁可在这里明确失败。
        QwenModel.verifyMmprojMatches(picked.first, mmProjPath)?.let { reason ->
            throw ModelInitializationException(reason)
        }

        Log.i(TAG, "加载模型: ${picked.first} backend=${config.backend} gpuLayers=${config.gpuLayerCount}")
        loadedModelFileName = picked.first
        inferenceEngine.initialize(picked.second, mmProjPath)
        isModelLoaded = true
        Log.i(TAG, "Qwen3.5 GGUF 模型加载成功")
    }

    /**
     * 从 MediaStore 加载图片。
     *
     * [decodeTargetSize] 是**解码阶段**的最长边目标，必须是 2 的幂采样率的粒度：
     * `inSampleSize` 只能取 2 的幂，所以实际得到的位图会比它大最多 2 倍。
     *
     * 这里曾经硬编码 448。那是个真实的天花板 —— 4096x2304 的照片会被采样成
     * 512x288，之后再怎么设置下游的分辨率上限都无法恢复细节，图像 token 数因此
     * 长期停在 144。默认值现与 [ModelConfig.imageSide] 对齐。
     */
    private fun loadPhotoBitmap(photoId: String, decodeTargetSize: Int = config.imageSide): android.graphics.Bitmap {
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

        options.inSampleSize = calculateSampleSize(options.outWidth, options.outHeight, decodeTargetSize)
        options.inJustDecodeBounds = false
        // 采样后仍是 ARGB_8888；大图在视觉塔预处理里会被缩到 image_max_pixels，
        // 这里不额外降色彩深度，避免在需要细节时先丢一轮。
        options.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888

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
     * 构建针对照片的分析提示词（**用户指令部分**，JSON schema 由引擎追加）。
     *
     * 这段文本直接进 prefill，而 prefill 是单张耗时里的一段真实计算（0.8B + 512px
     * 下约 2 秒 / 270 token），所以写得越短越快。但**不能省掉分类词汇表** ——
     * 实测把整段压成"识别类型、主要物体与场景"之后，分类从 `[风景, 建筑]` 变成了
     * `[城市, 船只]`、`[城市, 水域, 船只]`：模型会自己发明分类。分类是相册筛选与
     * 分组的基础，必须是可控的固定集合。
     *
     * 所以这里是精简过的版本：保留"（风景、建筑、集体合影等）"这类枚举，去掉原先
     * 4-5 条编号指令里与 schema 重复的部分（那些要求 schema 的字段已经说明过）。
     */
    private fun buildAnalysisPrompt(photo: Photo): String {
        val isScreenshot = photo.fileName.lowercase().contains("screenshot") ||
                          photo.fileName.lowercase().contains("截图")

        return when {
            isScreenshot -> "手机截图。识别截图类型（聊天、票据、文档、社交媒体等）、图中文字，并给出标签与一句话描述。"

            photo.width > photo.height * 1.5 -> "横向照片。识别类型（风景、建筑、集体合影等）、主要物体与场景、图中文字，并给出标签。"

            else -> "照片。识别类型（人物、美食、物品、文档等）、主要内容与场景、图中文字，并给出标签。"
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
        private const val TAG = "QwenPhotoContentAnalyzer"
    }
}
