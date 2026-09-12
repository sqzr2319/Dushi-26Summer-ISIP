package com.example.isip.data.ai

import android.content.Context
import android.util.Log
import com.example.isip.data.AppSettingsRepository
import com.example.isip.data.InferenceMode
import com.example.isip.data.model.Photo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * App-level photo analyzer. AI analysis is performed by the on-device Qwen3.5
 * model; when it is unavailable, the analyzer falls back to rule-based results.
 */
class HybridPhotoContentAnalyzer(
    context: Context,
    /**
     * 生产配置。默认取 [PRODUCTION_CONFIG]，不要在这里另写一份字面量。
     *
     * 显式写全而不是靠 `ModelConfig()` 的默认值，因为这正是 App（GalleryViewModel /
     * PhotoDetailViewModel）实际使用的配置 —— 这里的每个数字都直接决定线上行为，
     * 不该藏在默认参数里。
     */
    private val config: ModelConfig = PRODUCTION_CONFIG,
    /**
     * 单张分析超时。
     *
     * **360 秒。** 这个值必须容得下**冷启动的第一张**，那是全流程最慢的一次：
     * 实测（2B，NPU，768px）进程刚启动后的第一张要 **252 秒**，其中
     * `clip_image_batch_encode` 一个阶段就占 223 秒 —— 视觉塔（668 MB）首次
     * mmap 读入、页缓存全冷。同一进程的第二张只要 59 秒。
     *
     * 起初写的是 300 秒，对这个 252 秒只留了 48 秒余量 —— 设备稍慢一点，
     * 首张就会被判超时并降级成"基础分类结果"，也就是**把冷启动误报成模型不可用**。
     * 这类"慢被当成坏"的误报在本项目出现过多次，所以这里按最坏情况留足：
     * 360 秒 ≈ 冷启动实测的 1.4 倍、稳态（59–65 秒）的 5.5 倍。
     *
     * 若换成 4B（稳态 127–165 秒），这个值同样够用。
     */
    private val localTimeout: Long = 360_000L
) : PhotoContentAnalyzer {

    private val settingsRepository = AppSettingsRepository(context)
    private val qwenAnalyzer = QwenPhotoContentAnalyzer(context, config)
    private var localAvailable = true
    private var usedQwen = false

    override val modelName: String
        get() = if (usedQwen) qwenAnalyzer.modelName else "qwen-fallback"

    override val modelVersion: String
        get() = if (usedQwen) qwenAnalyzer.modelVersion else "1.0"

    override suspend fun analyze(photo: Photo): PhotoContentAnalysis {
        if (settingsRepository.read().inferenceMode == InferenceMode.RULES) {
            return createFallbackAnalysis(photo)
        }
        if (!localAvailable) return createFallbackAnalysis(photo)

        // 把诊断覆盖推给内层分析器。生产路径（本方法）是基准默认走的那条，
        // 不推的话基准设的 imageSide / maxTokens 完全不起作用。
        qwenAnalyzer.maxTokensOverrideForDiagnostics = maxTokensOverrideForDiagnostics
        qwenAnalyzer.maxImageSideOverrideForDiagnostics = maxImageSideOverrideForDiagnostics
        qwenAnalyzer.temperatureOverrideForDiagnostics = temperatureOverrideForDiagnostics

        return try {
            withTimeout(localTimeout) { qwenAnalyzer.analyze(photo) }.also {
                usedQwen = true
                Log.d(TAG, "使用本地 Qwen3.5 模型完成分析")
            }
        } catch (error: Exception) {
            usedQwen = false
            when (error) {
                is ModelInitializationException -> localAvailable = false
                is TimeoutCancellationException -> Log.e(TAG, "Qwen3.5 本地推理超时", error)
                else -> Log.e(TAG, "Qwen3.5 本地推理失败", error)
            }
            createFallbackAnalysis(photo, error)
        }
    }

    fun resetAvailability() {
        localAvailable = true
        usedQwen = false
    }

    /**
     * 批量流水线是否已启用。
     *
     * 默认关闭：它要求视觉塔线程数降到 4 左右才有效（8 线程与 LLM 争抢 CPU，
     * 实测比串行还慢）。见 [ModelConfig.enableBatchPipeline]。
     */
    fun isBatchPipelineEnabled(): Boolean =
        (config as? ModelConfig)?.enableBatchPipeline == true || pipelineEnabledForDiagnostics

    /** 仅诊断用：不重编译也能打开流水线，用于对照测量。 */
    var pipelineEnabledForDiagnostics: Boolean = false

    fun getStatus(): AnalyzerStatus = AnalyzerStatus(
        localAvailable = localAvailable,
        cloudAvailable = false,
        currentMode = if (usedQwen) "local-qwen3.5" else "fallback-rules"
    )

    suspend fun forceLocal(photo: Photo): PhotoContentAnalysis =
        withTimeout(localTimeout) { qwenAnalyzer.analyze(photo) }

    /**
     * 用给定提示词强制走本地模型，并保持与 [analyze] 相同的超时与降级语义。
     *
     * 仅供诊断：用来把"模型能力不足"与"生产提示词过载"分开验证。
     * [promptIsComplete] = true 时不追加 JSON schema 包装。
     */
    suspend fun forceLocalWithPrompt(
        photo: Photo,
        prompt: String,
        promptIsComplete: Boolean
    ): PhotoContentAnalysis = try {
        withTimeout(localTimeout) {
            qwenAnalyzer.analyzeWithPrompt(
                photo,
                prompt,
                promptIsComplete,
                maxTokensOverrideForDiagnostics,
                maxImageSideOverrideForDiagnostics,
                temperatureOverrideForDiagnostics
            )
        }.also { usedQwen = true }
    } catch (error: Exception) {
        usedQwen = false
        if (error is ModelInitializationException) localAvailable = false
        Log.e(TAG, "强制提示词推理失败", error)
        createFallbackAnalysis(photo, error)
    }

    fun release() = qwenAnalyzer.release()

    /**
     * 流水线批量分析：第 N+1 张的视觉塔编码与第 N 张的 LLM 推理重叠。
     *
     * **只对批量有意义。** 单张分析里 `视觉塔 → prefill` 是硬依赖，无法重叠；这个函数
     * 改善的是"连续分析很多张"的总耗时：串行时每张 `6s(CPU) + 11s(NPU) = 17s`，
     * 流水线后约 `max(6, 11) = 11s`，因为两块硬件本来就可以同时工作。
     *
     * 分工：
     * - **编码协程**（Dispatchers.Default）：加载照片 → 缩放 → JPEG → 视觉塔编码。
     *   全程只碰 clip，不碰 llama context。
     * - **当前协程**：从通道取已编码的图 → NPU prefill + 生成 → 解析 → emit。
     *
     * 通道容量取 2：够让编码线程领先一张，又不会把多张的 embeddings 同时压在内存里
     * （512px 下每张约 144 x 2048 x 4 字节 ≈ 1.2 MB，其实很小，但没必要无限缓冲）。
     *
     * 任何一张失败都只影响它自己 —— 编码失败或推理失败都会 emit 降级结果，
     * 不会中断整批（批量任务里因为一张图挂掉整批是不可接受的）。
     */
    fun analyzeBatch(photos: List<Photo>): Flow<Pair<Photo, PhotoContentAnalysis>> = flow {
        val channel = Channel<Encoded>(capacity = 2)

        val producer = CoroutineScope(Dispatchers.Default).launch {
            for (photo in photos) {
                var handle = 0L
                try {
                    val bytes = qwenAnalyzer.prepareImageBytes(photo)
                    if (bytes.isEmpty()) {
                        channel.send(Encoded(photo, 0L))
                        continue
                    }
                    handle = qwenAnalyzer.encodeImage(photo, bytes)
                    channel.send(Encoded(photo, handle))
                } catch (t: Throwable) {
                    Log.e(TAG, "流水线编码失败: ${photo.id}", t)
                    if (handle != 0L) qwenAnalyzer.freeEncoded(handle)
                    runCatching { channel.send(Encoded(photo, 0L)) }
                }
            }
            channel.close()
        }

        try {
            for (item in channel) {
                if (item.handle == 0L) {
                    usedQwen = false
                    emit(item.photo to createFallbackAnalysis(item.photo))
                    continue
                }
                val analysis = try {
                    withTimeout(localTimeout) { qwenAnalyzer.generateFromEncoded(item.photo, item.handle) }
                        .also { usedQwen = true }
                } catch (t: Throwable) {
                    usedQwen = false
                    if (t is ModelInitializationException) localAvailable = false
                    Log.e(TAG, "流水线推理失败: ${item.photo.id}", t)
                    createFallbackAnalysis(item.photo, t)
                } finally {
                    // 必须释放：句柄里是 chunks + embeddings 的堆内存
                    qwenAnalyzer.freeEncoded(item.handle)
                }
                emit(item.photo to analysis)
            }
        } finally {
            producer.cancel()
        }
    }

    private data class Encoded(val photo: Photo, val handle: Long)

    /**
     * 仅诊断用：覆盖相册路径的生成预算（maxTokens）。
     *
     * 为什么需要：生产配置是 `maxTokens = 256`，而实测 Qwen3.5-2B 在长生成下会退化
     * （跑满预算也不出 EOG、输出多语言乱码），短预算下同一路径输出连贯。把这个数字
     * 变成可注入的，才能复现并回归验证"可用性与预算强相关"这一结论。
     *
     * 生产路径不读这个字段；只有显式赋值才生效。
     */
    var maxTokensOverrideForDiagnostics: Int = -1

    /**
     * 仅诊断用：覆盖送入视觉塔前图片最长边的像素上限。
     *
     * 决定图像 token 数。llama.cpp 对 Qwen-VL 的建议是至少 1024 个图像 token，
     * 而 384px 的图只有 84-196 个 —— 这个开关用于做分辨率对照。
     */
    var maxImageSideOverrideForDiagnostics: Int = -1

    /** 仅诊断用：覆盖采样温度（<0 表示用 config 值）。 */
    var temperatureOverrideForDiagnostics: Float = -1f

    /**
     * 仅诊断用：让底层分析器直接从该文件读图片字节（原字节，跳过重编码）。
     * 见 [QwenPhotoContentAnalyzer.imageFileOverrideForDiagnostics]。
     */
    var imageFileOverrideForDiagnostics: String?
        get() = qwenAnalyzer.imageFileOverrideForDiagnostics
        set(value) { qwenAnalyzer.imageFileOverrideForDiagnostics = value }

    /** 最近一次实际送出的图片尺寸链路，用于确认分辨率覆盖是否生效。 */
    val lastSentImageDims: String
        get() = qwenAnalyzer.lastSentImageDims

    /** 最近一次模型返回的原始文本，用于确认字段有没有被生成/解析到。 */
    val lastRawResponse: String
        get() = qwenAnalyzer.lastRawResponse

    /**
     * 降级结果。
     *
     * [cause] 用来区分**超时**与**模型不可用** —— 这两者对用户的意义完全不同，
     * 之前它们共用同一句"模型未能运行"，把"设备忙、稍后重试即可"说成了"模型坏了"。
     * 实测确有此事：设备被其它 App 占用时，视觉塔一段就从 19 秒涨到 565 秒，
     * 触发超时并落进这条路径，而输出文字却在断言模型不可用。
     */
    private fun createFallbackAnalysis(photo: Photo, cause: Throwable? = null): PhotoContentAnalysis {
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
        val description = when (cause) {
            is TimeoutCancellationException ->
                "基础分类结果（分析超时，设备可能正忙 —— 稍后重新分析即可）"
            is ModelInitializationException ->
                "基础分类结果（本地模型不可用）"
            else ->
                "基础分类结果（Qwen3.5 模型未能运行）"
        }
        return PhotoContentAnalysis(
            categories = listOf(category),
            tags = tags,
            description = description,
            confidence = 0.3f,
            labels = tags.map { VisualLabel(it.removePrefix("#"), 0.3f) }
        )
    }

    companion object {
        private const val TAG = "QwenPhotoAnalyzer"

        /**
         * 生产路径唯一的一份配置。基准要单独覆盖某一项时，必须
         * `PRODUCTION_CONFIG.copy(...)`，而不是另写一份字面量。
         *
         * 这条规矩是踩出来的：诊断入口曾经为了"只换 backend"而自己构造了一份
         * `ModelConfig(backend = ..., maxTokens = 64, ...)`，结果当时代码里
         * `maxTokens` 恰好也是 64，看起来没问题；后来生产默认改成 512，那份拷贝
         * 没跟着改，于是"生产默认"的基准其实跑在 64 token 预算下 —— 测的又不是生产了。
         *
         * 各项取值的依据：
         * - `backend = AUTO`：NPU 优先，失败自动回落 CPU。**不含 Vulkan** ——
         *   实测 Vulkan 会算错（结构完整、内容乱码），见 [AccelerationBackend.GPU_VULKAN]。
         * - `maxTokens = 512`：纯兜底，模型遇到 EOG 会自己停（2B 实测在 171–214 处收尾），
         *   所以调大不会拖慢正常路径。定 512 是因为实测 4B 在 256 时会跑满且不 EOG，
         *   JSON 被从中间截断、解析降级成 `"……风光。",` 这种带引号尾逗号的残片。
         * - `temperature = 0.3`：产出是结构化 JSON，需要比 0.7 更稳的复现性。
         *
         * 注意这里**没有**写 `mmprojThreads`：它默认取设备核数（上限 8），是
         * 每台设备各自算出来的，写死反而会在低端机上过度订阅。见
         * [ModelConfig.mmprojThreads] 的实测对照表。
         */
        val PRODUCTION_CONFIG = ModelConfig(
            backend = AccelerationBackend.AUTO,
            numThreads = 4,
            maxTokens = 512,
            temperature = 0.3f,
            contextSize = 2048,
            quantizationType = QuantizationType.Q4_0
        )
    }
}

data class AnalyzerStatus(
    val localAvailable: Boolean,
    val cloudAvailable: Boolean,
    val currentMode: String
) {
    fun isAnyAvailable(): Boolean = localAvailable
    fun getPreferredMode(): String = if (localAvailable) "local-qwen3.5" else "fallback"
}
