package com.example.isip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.isip.data.ai.AccelerationBackend
import com.example.isip.data.ai.HybridPhotoContentAnalyzer
import com.example.isip.data.ai.LlamaCppWrapper
import com.example.isip.data.ai.ModelConfig
import com.example.isip.data.ai.QwenModel
import com.example.isip.data.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Manual multimodal test UI, plus an unattended benchmark mode.
 *
 * Launch the benchmark from a host with:
 *
 * ```
 * adb shell am start -n com.example.isip/.MultimodalTestActivity \
 *     --ez benchmark true --es backend CPU --ei runs 3
 * ```
 *
 * It logs one `BENCH` line per run and a final `BENCH_RESULT` line, then finishes.
 * Two launches with `backend CPU` and `backend GPU` give the A/B comparison, because
 * each launch runs in a fresh process with a fresh inference engine.
 */
class MultimodalTestActivity : ComponentActivity() {

    private lateinit var tvOutput: TextView
    private lateinit var btnLoadModel: Button
    private lateinit var btnTestText: Button
    private lateinit var btnTestImage: Button
    private lateinit var btnSelectImage: Button

    private var llamaWrapper: LlamaCppWrapper? = null
    private var selectedImageBytes: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multimodal_test)

        tvOutput = findViewById(R.id.tv_output)
        btnLoadModel = findViewById(R.id.btn_load_model)
        btnTestText = findViewById(R.id.btn_test_text)
        btnTestImage = findViewById(R.id.btn_test_image)
        btnSelectImage = findViewById(R.id.btn_select_image)

        // 请求存储权限
        requestPermissions()

        // 加载模型
        btnLoadModel.setOnClickListener {
            loadModel()
        }

        // 测试纯文本
        btnTestText.setOnClickListener {
            testTextPrompt()
        }

        // 选择图片
        btnSelectImage.setOnClickListener {
            selectImage()
        }

        // 测试多模态
        btnTestImage.setOnClickListener {
            testMultimodalPrompt()
        }

        if (intent?.getBooleanExtra(EXTRA_BENCHMARK, false) == true) {
            applyNpuPolicyOverride(intent?.getStringExtra(EXTRA_NPU_STATE))

            // 真·App 路径模式：用**与 GalleryViewModel / PhotoDetailViewModel 完全相同**的
            // 对象图跑一遍，包括 MobileCLIP 判定与 Room 落库。
            //
            // 为什么需要它，而不满足于 photoAnalysis：photoAnalysis 直接构造分析器并
            // 调用 analyze()，绕过了真实 App 的两层决策 ——
            //   1. AnalyzeImageSkill 会不会真的调用 Qwen（MobileCLIP 可用且置信度 >= 0.30
            //      时它根本不调，那条路径下"2B+NPU"是空谈）；
            //   2. AnalyzePhotosUseCase → PhotoRepository.saveAnalysisResult 有没有把结果
            //      真的写进 photo_ai 表（里面有一条 photo 查不到就静默 return 的分支）。
            //
            // 用法：--ez appPath true --ei photoCount 2 --ez npuState reset
            if (intent?.getBooleanExtra(EXTRA_APP_PATH, false) == true) {
                runAppPathAnalysis(
                    count = intent?.getIntExtra(EXTRA_PHOTO_COUNT, 1) ?: 1,
                    skip = intent?.getIntExtra(EXTRA_PHOTO_SKIP, 0) ?: 0,
                    batch = intent?.getBooleanExtra(EXTRA_APP_PATH_BATCH, false) == true
                )
                return
            }

            // 真·端到端模式：走生产分析器（HybridPhotoContentAnalyzer → QwenPhotoContentAnalyzer
            // → QwenInferenceEngine → LlamaCppWrapper），在真实相册照片上跑一遍。
            //
            // 这是唯一能回答"相册分析到底有没有吃到 NPU"的测试：合成图基准绕过了
            // 生产链路，只验证了后端本身。用法：
            //   adb shell am start -n com.example.isip/.MultimodalTestActivity \
            //       --ez photoAnalysis true --es npuState reset --ei photoCount 2
            // 流水线模式：连续分析多张，视觉塔（CPU）与 LLM（NPU）重叠。
            //
            // 与 photoAnalysis 的对照价值：两者的单张输出应当一致，差别只在总耗时。
            // 串行每张 = 视觉塔 6s + LLM 11s；流水线约 = max(6, 11)。
            if (intent?.getBooleanExtra(EXTRA_PIPELINE, false) == true) {
                runPipelineAnalysis(
                    count = intent?.getIntExtra(EXTRA_PHOTO_COUNT, 4) ?: 4,
                    skip = intent?.getIntExtra(EXTRA_PHOTO_SKIP, 0) ?: 0
                )
                return
            }

            if (intent?.getBooleanExtra(EXTRA_PHOTO_ANALYSIS, false) == true) {
                runRealPhotoAnalysis(
                    count = intent?.getIntExtra(EXTRA_PHOTO_COUNT, 1) ?: 1,
                    skip = intent?.getIntExtra(EXTRA_PHOTO_SKIP, 0) ?: 0,
                    excludeScreenshots =
                        intent?.getBooleanExtra(EXTRA_PHOTO_EXCLUDE_SCREENSHOTS, false) == true,
                    backend = if (intent?.hasExtra(EXTRA_BACKEND) == true) {
                        parseBackend(intent?.getStringExtra(EXTRA_BACKEND))
                    } else {
                        null
                    },
                    numThreads = intent?.getIntExtra(EXTRA_THREADS, -1) ?: -1,
                    mmprojUseGpu = intent?.getIntExtra(EXTRA_MMPROJ_GPU, -1) ?: -1,
                    mmprojThreads = intent?.getIntExtra(EXTRA_MMPROJ_THREADS, -1) ?: -1,
                    kvQuant = intent?.getIntExtra(EXTRA_KV_QUANT, -1) ?: -1,
                    hideVulkan = if (intent?.hasExtra(EXTRA_HIDE_VULKAN) == true) {
                        intent.getIntExtra(EXTRA_HIDE_VULKAN, 1) == 1
                    } else {
                        null
                    },
                    flashAttn = intent?.getIntExtra(EXTRA_FLASH_ATTN, -1) ?: -1
                )
                return
            }

            runBenchmark(
                backend = parseBackend(intent?.getStringExtra(EXTRA_BACKEND)),
                runs = intent?.getIntExtra(EXTRA_RUNS, 3) ?: 3,
                maxTokens = intent?.getIntExtra(EXTRA_MAX_TOKENS, 64) ?: 64,
                // -1 = 使用后端的默认层数；其它值用于二分定位"多少层时 GPU 才开始挂"
                gpuLayersOverride = intent?.getIntExtra(EXTRA_GPU_LAYERS, -1) ?: -1
            )
        }
    }

    /**
     * 用**真实 App 的对象图**跑一遍分析，并核验结果真的落库了。
     *
     * 构造方式必须与 [com.example.isip.ui.gallery.GalleryViewModel] /
     * [com.example.isip.ui.photo.PhotoDetailViewModel] 逐字一致：
     * `AnalyzePhotosUseCase(repository, HybridPhotoContentAnalyzer(application),
     * MobileClipProvider.getOrNull(application))`。任何一处不同，测的就不是 App 了。
     *
     * `batch = true` 时走 [com.example.isip.domain.usecase.AnalyzePhotosUseCase.analyzeAllPhotos]
     * —— 那是相册批量入口，也是**流水线真正生效的地方**（`analyzeSinglePhoto` 是单张
     * 接口，不走流水线）。用 limit 限制张数，否则会分析整个相册。
     */
    private fun runAppPathAnalysis(count: Int, skip: Int, batch: Boolean = false) {
        // 必须常亮。这个模式跑在 lifecycleScope 上，Activity 一旦离开 RESUMED
        // 协程就会挂起，推理停在半路 —— 表现是 native.log 停在某个 decode token、
        // 进程还活着、也没有任何崩溃记录（实测无人值守 8 小时后就是这样停住的，
        // 一度被误判成"某张照片让 NPU 卡死"）。屏幕超时锁屏是最常见的触发方式。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            benchLog("=== APP-PATH START count=$count skip=$skip ===")
            val app = applicationContext
            val repo = com.example.isip.data.PhotoRepository.getInstance(app)

            // 第 1 层决策：MobileCLIP 是否可用。它决定了 AnalyzeImageSkill 会不会
            // 走到 Qwen —— clip != null 且置信度 >= 0.30 且非文档类时，Qwen 根本不被调用。
            val clip = com.example.isip.data.ai.MobileClipProvider.getOrNull(app)
            benchLog(
                "MobileCLIP: " + if (clip == null)
                    "不可用（clip==null → AnalyzeImageSkill 对每张图都会走精细分析）"
                else "可用 ${com.example.isip.data.ai.MobileClipEngine.MODEL_NAME}"
            )
            if (clip != null) {
                benchLog("⚠️ CLIP 可用时，只有低置信度(<0.30)、截图/文档类或强制重析才会调用 Qwen")
            }

            val analyzer = HybridPhotoContentAnalyzer(app)
            val useCase = com.example.isip.domain.usecase.AnalyzePhotosUseCase(repo, analyzer, clip)

            val before = repo.getAllAnalysisResults().size
            benchLog("分析前 photo_ai 行数: $before")

            try {
                val pool = repo.getAllPhotos().filterNot { p ->
                    val n = p.fileName.lowercase()
                    "screenshot" in n || "截图" in n || "screen_shot" in n
                }
                val targets = pool.drop(skip).take(count)
                if (targets.isEmpty()) {
                    benchLog("没有候选照片")
                }

                if (batch) {
                    // 相册批量入口 —— 流水线真正生效的那条路径。
                    // 用 limit 限制张数，否则会分析整个相册（13k+ 张）。
                    //
                    // 注意 analyzeAllPhotos 会跳过"已有当前模型结果"的照片，所以
                    // 要先用 force 清掉目标照片的结果，才能保证它真的分析。
                    targets.forEach { useCase.analyzeSinglePhoto(it.id, force = true) }
                    benchLog("--- 走 analyzeAllPhotos(limit=$count)（流水线路径）---")
                    val t0 = System.currentTimeMillis()
                    var n = 0
                    useCase.analyzeAllPhotos(limit = count).collect { p ->
                        if (p.message.startsWith("Analyzed") || p.message.startsWith("Failed")) {
                            n++
                            benchLog("[$n] ${System.currentTimeMillis() - t0} ms  ${p.message}")
                        }
                    }
                    benchLog("批量总耗时: ${System.currentTimeMillis() - t0} ms / $n 张")
                } else {
                    targets.forEachIndexed { i, photo ->
                        benchLog("--- [$i] ${photo.fileName} (assetId=${photo.id}) ---")
                        // force=true：每次都强制走完整链路，否则第二次运行会命中
                        // "已是当前模型的结果" 而被跳过，回归就失去意义。
                        //
                        // 注意 force=true 对应 requireDetail=true（用户点"重新分析"的语义），
                        // 与批量路径的 requireDetail=false 不完全相同 —— 但在本机
                        // MobileCLIP 不可用（clip==null）的前提下，两者都会调用 Qwen，
                        // 所以对"Qwen 是否被调用、结果是否落库"这两个问题的验证等价。
                        val t0 = System.currentTimeMillis()
                        val r = useCase.analyzeSinglePhoto(photo.id, force = true)
                        benchLog("耗时: ${System.currentTimeMillis() - t0} ms")
                        if (r == null) {
                            benchLog("返回: null（Repository 里没有这张照片？）")
                        } else {
                            benchLog("分类: ${r.categories}")
                            benchLog("标签: ${r.tags}")
                            benchLog("描述: ${r.description.take(200)}")
                            benchLog("模型: ${r.modelName} / ${r.modelVersion} 置信度=${r.confidence}")
                        }
                    }
                }

                val after = repo.getAllAnalysisResults().size
                benchLog("分析后 photo_ai 行数: $after （本次新增 ${after - before}）")
                if (after == before) {
                    benchLog("❌ 结果没有落库 —— 检查 saveAnalysisResult 的 photo 查找是否命中")
                }
            } catch (t: Throwable) {
                benchLog("APP-PATH ERROR ${t.javaClass.name}: ${t.message}")
            } finally {
                analyzer.release()
                benchLog("=== APP-PATH END ===")
                finish()
            }
        }
    }

    /**
     * 流水线批量分析：视觉塔（CPU）与 LLM（NPU）重叠。
     *
     * 记录的是**累计耗时**，因为流水线的意义在总时长而不是单张 —— 单张内部
     * `视觉塔 → prefill` 有硬依赖，无法重叠。
     */
    private fun runPipelineAnalysis(count: Int, skip: Int) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            benchLog("=== PIPELINE START count=$count skip=$skip ===")
            val repo = PhotoRepository.getInstance(applicationContext)
            val analyzer = HybridPhotoContentAnalyzer(applicationContext)
            try {
                val pool = repo.getAllPhotos().filterNot { p ->
                    val n = p.fileName.lowercase()
                    "screenshot" in n || "截图" in n || "screen_shot" in n
                }
                val targets = pool.drop(skip).take(count)
                benchLog("目标张数: ${targets.size}")

                val t0 = System.currentTimeMillis()
                var i = 0
                analyzer.analyzeBatch(targets).collect { (photo, r) ->
                    i++
                    val elapsed = System.currentTimeMillis() - t0
                    benchLog("[$i/${targets.size}] 累计 ${elapsed} ms  ${photo.fileName}")
                    benchLog("     分类: ${r.categories}")
                    benchLog("     标签: ${r.tags}")
                    benchLog("     描述: ${r.description.take(160)}")
                }
                val total = System.currentTimeMillis() - t0
                benchLog("流水线总耗时: $total ms / $i 张 = ${if (i > 0) total / i else 0} ms/张")
                benchLog("对照：串行模式约 17000-20000 ms/张")
            } catch (t: Throwable) {
                benchLog("PIPELINE ERROR ${t.javaClass.name}: ${t.message}")
            } finally {
                analyzer.release()
                benchLog("=== PIPELINE END ===")
                finish()
            }
        }
    }

    /** 跑一条并记录结果，供真实照片与合成对照共用。 */
    private suspend fun runOne(
        analyzer: HybridPhotoContentAnalyzer,
        photo: com.example.isip.data.model.Photo,
        promptOverride: String?,
        maxTokensOverride: Int = -1,
        imageSideOverride: Int = -1,
        temperatureOverride: Float = -1f
    ) {
        if (maxTokensOverride > 0) {
            analyzer.maxTokensOverrideForDiagnostics = maxTokensOverride
        }
        // 每次都显式赋值（包括 -1），否则上一次的覆盖会残留到下一张。
        analyzer.maxImageSideOverrideForDiagnostics = imageSideOverride
        analyzer.temperatureOverrideForDiagnostics = temperatureOverride

        val t0 = System.currentTimeMillis()
        val result = if (promptOverride != null) {
            analyzer.forceLocalWithPrompt(photo, promptOverride, promptIsComplete = true)
        } else {
            analyzer.analyze(photo)
        }
        val ms = System.currentTimeMillis() - t0
        benchLog("耗时: ${ms} ms")
        benchLog("图片尺寸链路: ${analyzer.lastSentImageDims}")
        // 换行替换成空格：模型常返回多行缩进的 JSON，不折叠的话 benchmark.txt 里
        // 只会看到第一行的 "{"，等于没记。
        benchLog("模型原始返回: ${analyzer.lastRawResponse.replace("\n", " ").replace("\r", "")}")
        benchLog("分类: ${result.categories}")
        benchLog("标签: ${result.tags}")
        benchLog("描述: ${result.description.take(200)}")
        benchLog("置信度: ${result.confidence}")
    }

    /**
     * 合成场景图对照：写进 MediaStore 后交给同一套相册分析路径。
     *
     * 目的是把变量收敛到"图像内容"这一个：提示词、图片解码、缩放、JNI 调用全部相同。
     */
    private suspend fun runSyntheticProbe(
        analyzer: HybridPhotoContentAnalyzer,
        promptOverride: String?,
        maxTokensOverride: Int = -1,
        imageSideOverride: Int = -1
    ) {
        val bytes = withContext(Dispatchers.Default) { syntheticSceneJpeg(448, 448) }
        val name = "isip_synthetic_probe_${System.currentTimeMillis()}.jpg"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/isip_probe")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        // 用外部内容 URI 而非 insert 返回的 uri：PhotoContentAnalyzer.loadPhotoBitmap
        // 是按 id 拼 ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, id) 读取的，
        // 只有前者的最后一段路径与 id 一致。
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
        if (uri == null) {
            benchLog("合成对照: 无法写入 MediaStore")
            return
        }
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            resolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null
            )
            val id = uri.lastPathSegment ?: return
            val canonical = android.content.ContentUris.withAppendedId(collection, id.toLong())
            val now = System.currentTimeMillis()
            val photo = com.example.isip.data.model.Photo(
                id = id,
                filePath = canonical.toString(),
                fileName = name,
                dateTaken = now,
                dateModified = now,
                latitude = null,
                longitude = null,
                sizeBytes = bytes.size.toLong(),
                width = 448,
                height = 448
            )
            runOne(analyzer, photo, promptOverride, maxTokensOverride, imageSideOverride)
        } finally {
            // 对照图是一次性的，跑完就删，别污染用户相册
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    /**
     * 在真实照片上跑生产分析链路，并如实报告最终生效的加速级别。
     *
     * 与 [runBenchmark] 的区别：基准自己构造 `LlamaCppWrapper` 并直接调原生接口，
     * 而这里用的是相册真正走的对象图，因此也覆盖了 AcceleratorPolicy 在生产配置
     * （`backend=AUTO`）下的行为。
     */
    private fun runRealPhotoAnalysis(
        count: Int,
        skip: Int = 0,
        excludeScreenshots: Boolean = false,
        backend: AccelerationBackend? = null,
        numThreads: Int = -1,
        mmprojUseGpu: Int = -1,
        mmprojThreads: Int = -1,
        kvQuant: Int = -1,
        hideVulkan: Boolean? = null,
        flashAttn: Int = -1
    ) {
        lifecycleScope.launch {
            benchLog("=== PHOTO-ANALYSIS START count=$count skip=$skip excludeScreenshots=$excludeScreenshots backend=${backend ?: "AUTO(生产默认)"} threads=$numThreads mmprojGpu=$mmprojUseGpu mmprojThreads=$mmprojThreads kvQuant=$kvQuant hideVulkan=$hideVulkan ===")
            val repo = PhotoRepository.getInstance(applicationContext)
            // 不传任何覆盖时保持生产默认；传了才换。
            //
            // 关键：换任何一项都必须基于 PRODUCTION_CONFIG 做 copy，不能另写一份
            // ModelConfig 字面量 —— 那样一旦生产默认值变化，基准就跟生产脱节，
            // "基准测的就是生产配置"这个前提就没了（这个坑真踩过：那份拷贝里的
            // maxTokens 停在 64，而生产已经改成 512）。
            val overridden = backend != null || numThreads > 0 || mmprojUseGpu >= 0 ||
                mmprojThreads > 0 || kvQuant >= 0 || hideVulkan != null || flashAttn >= 0
            val analyzer = if (!overridden) {
                HybridPhotoContentAnalyzer(applicationContext)
            } else {
                var cfg = HybridPhotoContentAnalyzer.PRODUCTION_CONFIG
                if (backend != null) cfg = cfg.copy(backend = backend)
                if (numThreads > 0) cfg = cfg.copy(numThreads = numThreads)
                if (mmprojUseGpu >= 0) cfg = cfg.copy(mmprojUseGpu = mmprojUseGpu == 1)
                if (mmprojThreads > 0) cfg = cfg.copy(mmprojThreads = mmprojThreads)
                if (kvQuant >= 0) cfg = cfg.copy(kvQuant = kvQuant)
                if (hideVulkan != null) cfg = cfg.copy(hideVulkan = hideVulkan)
                if (flashAttn >= 0) cfg = cfg.copy(flashAttnMode = flashAttn)
                benchLog("生效配置: threads=${cfg.numThreads} mmprojThreads=${cfg.mmprojThreads} backend=${cfg.backend} hideVulkan=${cfg.hideVulkan} kvQuant=${cfg.kvQuant} flashAttn=${cfg.flashAttnMode} maxTokens=${cfg.maxTokens}")
                HybridPhotoContentAnalyzer(applicationContext, cfg)
            }

            try {
                val all = repo.getAllPhotos()
                benchLog("相册照片总数: ${all.size}")
                if (all.isEmpty()) {
                    benchLog("PHOTO-ANALYSIS RESULT status=no-photos（设备相册为空或未授权）")
                    finish()
                    return@launch
                }

                // 允许跳过与排除截图：实测截图与真实照片在视觉塔上表现不同，做对照时
                // 必须能选定类别，否则"最近的照片"恰好是截图，结论会被误导。
                val pool = if (excludeScreenshots) {
                    all.filterNot { p ->
                        val n = p.fileName.lowercase()
                        "screenshot" in n || "截图" in n || "screen_shot" in n
                    }
                } else {
                    all
                }
                benchLog("过滤后候选: ${pool.size}")
                val targets = pool.drop(skip).take(count)
                if (targets.isEmpty()) {
                    benchLog("PHOTO-ANALYSIS RESULT status=no-targets（skip=$skip 超出候选数 ${pool.size}）")
                    finish()
                    return@launch
                }

                // 可选的提示词覆盖：用于把"模型能力"与"提示词复杂度"分开验证。
                // 生产提示词是详细中文指令 + 6 字段 JSON schema（约 190 token），
                // 对 2B 模型可能过载；用短提示词对照才能定因。
                // 传 `--es promptOverride "..."` 时，该提示词作为**完整**提示词直接送入，
                // 不再追加 JSON schema（promptIsComplete=true），否则对照实验会被撑大。
                // 可选：覆盖相册路径的生成预算。
                //
                // 必要性：实测 Qwen3.5-2B 在长生成下会退化（跑满预算不 EOG、输出乱码），
                // 而生产配置是 maxTokens=256。把预算压小后同一路径输出连贯，说明可用性
                // 与预算强相关 —— 这个开关让这件事可复现、可回归。
                val maxTokensOverride = intent?.getIntExtra(EXTRA_MAX_TOKENS, -1) ?: -1
                if (maxTokensOverride > 0) {
                    benchLog("生成预算覆盖: $maxTokensOverride")
                }

                // 可选：覆盖送入视觉塔前图片最长边的像素上限。
                // 图像 token 数随它平方增长；llama.cpp 对 Qwen-VL 的建议是至少 1024 个
                // 图像 token，而 384px 只有 84-196 个。用它做分辨率对照。
                val imageSideOverride = intent?.getIntExtra(EXTRA_IMAGE_SIDE, -1) ?: -1
                if (imageSideOverride > 0) {
                    benchLog("视觉分辨率覆盖(最长边): $imageSideOverride px")
                }

                // 可选：覆盖采样温度。基准路径用 0f（贪心）能产出正确描述，而生产用
                // config.temperature=0.3 时同一张图是乱码，必须能单独控制这个变量。
                val temperatureOverride = intent?.getFloatExtra(EXTRA_TEMPERATURE, -1f) ?: -1f
                if (temperatureOverride >= 0f) {
                    benchLog("温度覆盖: $temperatureOverride")
                }

                // 可选：让相册路径直接读这个图片文件（原字节，不重编码）。
                // 用于把"图片经过 BitmapFactory 解码+重编码"与"后续代码路径"分开。
                val imageFileOverride = intent?.getStringExtra(EXTRA_PHOTO_IMAGE_FILE)
                if (imageFileOverride != null) {
                    benchLog("相册路径图片文件覆盖: $imageFileOverride")
                }
                analyzer.imageFileOverrideForDiagnostics = imageFileOverride

                val promptOverride = intent?.getStringExtra(EXTRA_PROMPT_OVERRIDE)
                if (promptOverride != null) {
                    benchLog("提示词覆盖已启用（完整提示词，不追加 schema）")
                }

                // 可选：在真实照片之外，再插入一张**合成场景图**作为对照，两者共用
                // 完全相同的提示词与代码路径。这是判别"模型处理不了真实照片内容"与
                // "相册路径有问题"的最短实验：合成图已知可用。
                val synthProbe = intent?.getBooleanExtra(EXTRA_SYNTHETIC_PROBE, false) == true

                targets.forEachIndexed { i, photo ->
                    benchLog("--- [$i] REAL ${photo.fileName} (id=${photo.id}) ---")
                    runOne(
                        analyzer, photo, promptOverride, maxTokensOverride,
                        imageSideOverride, temperatureOverride
                    )

                    if (synthProbe) {
                        benchLog("--- [$i] SYNTHETIC control (same prompt & path) ---")
                        runSyntheticProbe(
                            analyzer, promptOverride, maxTokensOverride, imageSideOverride
                        )
                    }
                }

                val status = analyzer.getStatus()
                benchLog("分析器状态: mode=${status.currentMode} localAvailable=${status.localAvailable}")
                benchLog(
                    "PHOTO-ANALYSIS RESULT model=${analyzer.modelName} " +
                        "mode=${status.currentMode} photos=${targets.size}"
                )
            } catch (t: Throwable) {
                benchLog("PHOTO-ANALYSIS ERROR ${t.javaClass.name}: ${t.message}")
                t.stackTrace.take(8).forEach { benchLog("   at $it") }
            } finally {
                analyzer.release()
                benchLog("=== PHOTO-ANALYSIS END ===")
                finish()
            }
        }
    }

    /**
     * 把 AcceleratorPolicy 摆到指定状态，用于验证 AUTO 路径的两种分支。
     *
     * 为什么需要这个钩子：生产路径是 `backend=AUTO` + AcceleratorPolicy 决策，
     * 而策略状态存在 SharedPreferences 里、进程外改不了。没有这个入口就只能靠
     * "真的让 NPU 失败两次"来测试降级，既慢又不可复现。
     *
     * 取值：
     * - `reset`（默认）：清失败计数、允许 NPU → 期望 AUTO 走 NPU
     * - `disable`：用户关闭 NPU → 期望 AUTO 不加载 hexagon 模块
     * - `exhaust`：失败计数压到阈值 → 期望 AUTO 自动降级、不加载 hexagon 模块
     * - `clear`：只清掉"推理进行中"标记，不动其它字段。用于测试中强杀进程后
     *   复位状态 —— 否则下次启动会把那次强杀误判成崩溃并记一笔失败。
     */
    private fun applyNpuPolicyOverride(state: String?) {
        if (state.isNullOrBlank()) return
        val policy = com.example.isip.data.ai.AcceleratorPolicy(applicationContext)
        when (state.lowercase()) {
            "reset" -> policy.resetNpu()
            "disable" -> policy.disableNpu()
            "clear" -> policy.markInferenceCompleted()
            "exhaust" -> {
                policy.resetNpu()
                repeat(com.example.isip.data.ai.AcceleratorPolicy.FAILURE_THRESHOLD) {
                    policy.recordNpuFailure("由 npuState=exhaust 注入的测试失败")
                }
            }
            else -> Log.w(TAG, "未知 npuState: $state（忽略）")
        }
        Log.i(TAG, "NPU 策略已置为 $state -> ${policy.describe()}")
    }

    private fun parseBackend(raw: String?): AccelerationBackend = when (raw?.uppercase()) {
        "CPU" -> AccelerationBackend.CPU
        "GPU", "VULKAN", "GPU_VULKAN" -> AccelerationBackend.GPU_VULKAN
        "NPU", "HEXAGON", "NPU_HEXAGON" -> AccelerationBackend.NPU_HEXAGON
        else -> AccelerationBackend.AUTO
    }

    // ---------------------------------------------------------------------
    // 无人值守基准
    // ---------------------------------------------------------------------

    /**
     * 同时写 logcat 和文件。
     *
     * 这台设备（Android 16 / MIUI）不会把应用自己的 logcat 输出暴露给 adb，
     * 所以基准结果必须以文件为准，否则跑完什么也拿不到。
     * 读取方式：`adb shell run-as com.example.isip cat files/bench/benchmark.txt`
     */
    private fun benchLog(line: String) {
        Log.i(TAG, line)
        try {
            val dir = java.io.File(filesDir, "bench").apply { mkdirs() }
            val file = java.io.File(dir, "benchmark.txt")
            val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            file.appendText("$stamp  $line\n")
        } catch (e: Exception) {
            Log.e(TAG, "写基准结果失败", e)
        }
    }

    private fun runBenchmark(
        backend: AccelerationBackend,
        runs: Int,
        maxTokens: Int,
        gpuLayersOverride: Int = -1
    ) {
        // 基准跑在 lifecycleScope 上，Activity 一旦离开 RESUMED 协程就会挂起；
        // 而屏幕超时锁屏会把应用推到后台，MIUI 随后冻结进程（实测 CPU 0.0%、
        // 十几分钟毫无进展）。所以基准期间必须常亮。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            benchLog("=== BENCH START backend=$backend runs=$runs maxTokens=$maxTokens gpuLayers=$gpuLayersOverride ===")
            try {
                val picked = QwenModel.ensurePreferredModel(this@MultimodalTestActivity)
                val mmProjPath = QwenModel.ensureAvailable(this@MultimodalTestActivity, QwenModel.MMPROJ_FILE_NAME)
                if (picked == null || mmProjPath == null) {
                    benchLog("BENCH_RESULT status=model-missing picked=$picked mmproj=$mmProjPath")
                    finish()
                    return@launch
                }
                val (modelFileName, modelPath) = picked
                benchLog("主模型: $modelFileName")
                benchLog("投影层: ${QwenModel.MMPROJ_FILE_NAME}")

                // 图片来源：优先用 --es imageFile 指定的真实图片文件，否则用合成场景图。
                //
                // 为什么需要文件入口：合成图在基准路径上稳定产出连贯结果，而真实照片经
                // 相册路径总是乱码。要判断差异来自"图片内容"还是"两条路径的实现"，必须
                // 能在**同一条受控路径**上喂真实照片。之前用 syntheticProbe 把图插进
                // MediaStore 再走相册路径，但那个写入步骤本身会让进程死掉，所以改用
                // 直接读文件。
                val imageFile = intent?.getStringExtra(EXTRA_IMAGE_FILE)
                val imageBytes = withContext(Dispatchers.Default) {
                    if (imageFile != null) {
                        val f = java.io.File(imageFile)
                        if (f.isFile) f.readBytes() else ByteArray(0)
                    } else {
                        syntheticSceneJpeg(
                            width = intent?.getIntExtra(EXTRA_IMAGE_W, 448) ?: 448,
                            height = intent?.getIntExtra(EXTRA_IMAGE_H, 448) ?: 448
                        )
                    }
                }
                if (imageBytes.isEmpty()) {
                    benchLog("BENCH_RESULT status=image-missing file=$imageFile")
                    finish()
                    return@launch
                }
                benchLog("测试图: ${imageBytes.size / 1024} KB  source=${imageFile ?: "synthetic"}")

                val wrapper = LlamaCppWrapper(applicationContext, ModelConfig(
                    backend = backend,
                    numThreads = 4,
                    maxTokens = maxTokens,
                    temperature = 0.1f,
                    contextSize = 2048,
                    gpuLayers = if (gpuLayersOverride >= 0) gpuLayersOverride else null,
                    // 只有显式选 NPU 时才在这里强制启用 —— 这条分支绕过
                    // AcceleratorPolicy，用于基准测试锁定后端。
                    //
                    // AUTO 必须留 false：那正是**生产路径**（相册分析的默认配置是
                    // backend=AUTO），此时由 AcceleratorPolicy 决定是否加载 Hexagon
                    // 模块。若这里也替 AUTO 做决定，基准测的就不是真实行为了。
                    enableHexagonNpu = backend == AccelerationBackend.NPU_HEXAGON,
                    // flash attention：0=自动 1=关闭 2=开启，用于定位 Vulkan 乱码。
                    flashAttnMode = intent?.getIntExtra(EXTRA_FLASH_ATTN, 0) ?: 0
                ))
                // 原生层日志写文件：设备不把应用 logcat 交给 adb，挂死点只能靠文件看。
                //
                // 必须排在 loadAcceleratorBackends() **之前**：NPU 决策、设备命中/
                // 未命中、ADSP_LIBRARY_PATH 这些诊断都是在那次调用里产生的，文件回调
                // 若还没装好，这些行就只去了 logcat（这台设备拿不到），等于白打。
                // ggml 后端的规避/调优开关全靠环境变量，App 只能进程内 setenv。
                // 必须在 loadAcceleratorBackends() 之前 —— Vulkan 设备初始化会读它们。
                val vkFlags = intent?.getStringExtra(EXTRA_VK_FLAGS)
                if (!vkFlags.isNullOrBlank()) {
                    vkFlags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { f ->
                        val name = if (f.startsWith("GGML_")) f else "GGML_VK_$f"
                        val rc = wrapper.setNativeEnv(name, "1")
                        benchLog("Vulkan 开关: $name=1 rc=$rc")
                    }
                }

                val nativeLog = wrapper.enableNativeFileLog()
                benchLog("原生日志: $nativeLog")

                // 必须在加载模型之前注册后端：ggml 注册表为空时加载模型会直接失败。
                // App 设不了环境变量，所以走显式加载（nativeLibraryDir 里随 APK 分发的模块）。
                //
                // NPU 决策和 HTP0 注册都发生在这**一次调用内部**，所以描述加速状态的
                // 日志一律排在它之后 —— 排前面读到的是"还没加载"的陈旧值（曾因此把
                // 实际走了 NPU 的运行报成"未尝试 NPU"）。
                val nBackends = wrapper.loadAcceleratorBackends()
                benchLog("后端注册数: $nBackends")
                benchLog("后端详情: ${wrapper.describeBackends()}")
                benchLog("加速策略: ${wrapper.describeAcceleration()}")
                benchLog("本次是否尝试 NPU: ${wrapper.wasNpuAttempted()}")

                val t0 = System.currentTimeMillis()
                val loaded = withContext(Dispatchers.IO) {
                    wrapper.loadModel(modelPath, mmProjPath)
                }
                val loadMs = System.currentTimeMillis() - t0

                if (!loaded) {
                    benchLog("BENCH_RESULT status=load-failed error=${wrapper.getLastError()}")
                    finish()
                    return@launch
                }

                benchLog("后端报告: ${wrapper.describeBackends()}")
                benchLog("BENCH backend=$backend loadMs=$loadMs")

                // ---- 文本 decode：干净地衡量"LLM 有多少层被卸载到加速器" ----
                // 不用图片，避免把视觉塔的 CPU 开销混进来；这条数值才是 A/B 对比的依据。
                // 贪心 + 固定前缀 => 每次生成的 token 序列完全一致，可比。
                val textPrompt = "请从1数到40，用中文，每个数字之间用逗号分隔，不要任何解释。"
                var textWarmup = 0L
                var textTotal = 0L
                var textChars = 0
                var textRate = 0.0
                // 可选：跳过文本阶段，只跑多模态。
                //
                // 必要性：相册路径每次都调 reset_context() 清空 KV 后再做多模态，而基准
                // 路径是先跑两轮文本、KV 里已有内容，随后才做多模态 —— 且基准成功、相册
                // 失败。要判断"先跑文本"是不是成功的前提，必须能在基准里关掉文本阶段。
                val skipText = intent?.getBooleanExtra(EXTRA_SKIP_TEXT, false) == true
                if (skipText) {
                    benchLog("跳过文本阶段（只跑多模态）")
                }
                repeat(if (skipText) 0 else runs) { i ->
                    val start = System.currentTimeMillis()
                    var out = ""
                    withContext(Dispatchers.IO) {
                        wrapper.generate(
                            prompt = textPrompt,
                            maxTokens = maxTokens,
                            temperature = 0f   // 贪心，保证可比
                        ) { r -> out = r }
                    }
                    val ms = System.currentTimeMillis() - start
                    textChars = out.length
                    val rate = if (ms > 0) maxTokens * 1000.0 / ms else 0.0
                    if (i == 0) textWarmup = ms else { textTotal += ms; textRate = rate }
                    benchLog("BENCH_TEXT backend=$backend run=$i ms=$ms chars=${out.length} tok_per_s=${"%.2f".format(rate)}")
                    // 记录实际文本：`chars=` 无法区分"正确数数"与"等长乱码"。
                    // Vulkan 在相册路径上产出乱码后，必须能回答"是 LLM 本身错了，
                    // 还是只有多模态那条路错了"，纯文本输出就是那条分界线。
                    benchLog("TEXT输出 run=$i: ${out.take(200)}")
                }
                val textSteady = if (runs > 1) textTotal / (runs - 1) else textTotal
                val steadyRate = if (textSteady > 0) maxTokens * 1000.0 / textSteady else 0.0
                benchLog(
                    "BENCH_TEXT_RESULT backend=$backend loadMs=$loadMs warmupMs=$textWarmup " +
                        "steadyMs=$textSteady tok_per_s=${"%.2f".format(steadyRate)} maxTokens=$maxTokens"
                )

                // ---- 多模态端到端：真实业务路径的耗时（含 CPU 视觉塔） ----
                var mmWarmup = 0L
                var mmTotal = 0L
                var mmChars = 0
                // 多模态用哪个提示词：优先取 --es prompt，否则用默认中文一句描述。
                //
                // 这里此前**硬编码**了 "用一句话描述这张图。"，完全忽略传入的 prompt。
                // 后果是：所有"换提示词"的对照实验其实都没生效，而每次多模态输出长度
                // 都稳定在 chars=30 —— 那正是这个固定提示词对应的固定回答，不是模型
                // 行为的证据。发现它之前，我基于这个数字做过多次错误归因。
                val mmPrompt = intent?.getStringExtra("prompt")?.takeIf { it.isNotBlank() }
                    ?: "用一句话描述这张图。"
                benchLog("多模态提示词: $mmPrompt")
                repeat(runs) { i ->
                    val start = System.currentTimeMillis()
                    var out = ""
                    withContext(Dispatchers.IO) {
                        wrapper.generateMultimodal(
                            imageData = imageBytes,
                            prompt = mmPrompt,
                            maxTokens = 48,
                            temperature = 0f
                        ) { result -> out = result }
                    }
                    val ms = System.currentTimeMillis() - start
                    mmChars = out.length
                    if (i == 0) mmWarmup = ms else mmTotal += ms
                    benchLog("BENCH_MM backend=$backend run=$i ms=$ms chars=${out.length}")
                    // 记录实际文本：只凭 chars=N 无法区分"连贯描述"与"等长乱码"，
                    // 而这正是判断分辨率/提示词是否影响语义的唯一依据。
                    benchLog("MM输出 run=$i: $out")
                }
                val mmSteady = if (runs > 1) mmTotal / (runs - 1) else mmTotal
                benchLog(
                    "BENCH_MM_RESULT backend=$backend warmupMs=$mmWarmup steadyMs=$mmSteady chars=$mmChars"
                )
                benchLog("BENCH_RESULT backend=$backend loadMs=$loadMs textTokPerS=${"%.2f".format(steadyRate)} mmSteadyMs=$mmSteady")
                wrapper.release()
            } catch (e: Exception) {
                benchLog("BENCH_RESULT status=exception ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "BENCH exception", e)
            } finally {
                benchLog("=== BENCH END backend=$backend ===")
                finish()
            }
        }
    }

    /**
     * 生成一张确定性的合成图（不是纯色），保证视觉编码器有真实工作量，
     * 否则不同后端之间的差异会被"输入太简单"掩盖。
     */
    /**
     * 生成一张合成场景图（天空 + 草地 + 太阳 + 一个方块）。
     *
     * 尺寸可配：真实照片走的是"最长边缩到 384"，因此是 384x216 这类非正方形。
     * 实测合成图 448x448 能正确描述、真实照片却输出乱码，必须能改变尺寸来判别
     * "尺寸/长宽比"还是"图像内容"造成的差异。
     */
    private fun syntheticSceneJpeg(width: Int = 448, height: Int = 448): ByteArray {
        val w = width
        val h = height
        val unit = minOf(w, h).toFloat()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(120, 170, 220))
        val paint = Paint()
        paint.color = Color.rgb(70, 130, 70)
        canvas.drawRect(0f, h * 0.65f, w.toFloat(), h.toFloat(), paint)
        paint.color = Color.rgb(240, 220, 120)
        canvas.drawCircle(w * 0.75f, h * 0.2f, unit * 0.1f, paint)
        paint.color = Color.rgb(90, 80, 70)
        canvas.drawRect(w * 0.2f, h * 0.4f, w * 0.45f, h * 0.7f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_IMAGES
        )
        val needRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needRequest) {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private fun selectImage() {
        val intent = Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
        imagePickerLauncher.launch(intent)
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            uri?.let {
                val inputStream = contentResolver.openInputStream(it)
                // 解码为 Bitmap 并缩放到最大 640px，防止 OOM
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
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
                    selectedImageBytes = outputStream.toByteArray()

                    if (scaledBitmap != bitmap) scaledBitmap.recycle()
                    bitmap.recycle()

                    val sizeKb = selectedImageBytes?.size?.div(1024) ?: 0
                    Toast.makeText(this, "✅ 图片已选择: ${sizeKb} KB", Toast.LENGTH_SHORT).show()
                    tvOutput.append("\n✅ 图片已加载，大小: ${sizeKb} KB")
                }
            }
        }
    }

    private fun loadModel() {
        btnLoadModel.isEnabled = false
        tvOutput.append("\n📥 加载模型中...")

        lifecycleScope.launch {
            try {
                llamaWrapper = LlamaCppWrapper(applicationContext)
                llamaWrapper?.enableNativeFileLog()
                val nBackends = llamaWrapper?.loadAcceleratorBackends() ?: 0
                tvOutput.append("\n后端注册数: $nBackends")
                tvOutput.append("\n后端详情: ${llamaWrapper?.describeBackends()}")

                val picked = QwenModel.ensurePreferredModel(this@MultimodalTestActivity)
                val mmprojPath = QwenModel.ensureAvailable(this@MultimodalTestActivity, QwenModel.MMPROJ_FILE_NAME)

                if (picked == null || mmprojPath == null) {
                    tvOutput.append("\n❌ 模型文件缺失（assets 与私有目录都没有）")
                    btnLoadModel.isEnabled = true
                    return@launch
                }
                tvOutput.append("\n主模型: ${picked.first}")

                val success = withContext(Dispatchers.IO) {
                    llamaWrapper?.loadModel(picked.second, mmprojPath) ?: false
                }

                if (success) {
                    tvOutput.append("\n✅ 模型加载成功！")
                    Toast.makeText(this@MultimodalTestActivity, "✅ 模型加载成功", Toast.LENGTH_SHORT).show()
                } else {
                    val errMsg = llamaWrapper?.getLastError() ?: "未知错误"
                    tvOutput.append("\n❌ 模型加载失败: $errMsg")
                    Toast.makeText(this@MultimodalTestActivity, "❌ 加载失败", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                tvOutput.append("\n❌ 加载异常: ${e.message}")
                e.printStackTrace()
            }
            btnLoadModel.isEnabled = true
        }
    }

    private fun testTextPrompt() {
        val wrapper = llamaWrapper
        if (wrapper == null) {
            tvOutput.append("\n⚠️ 请先加载模型！")
            return
        }

        tvOutput.append("\n📤 发送纯文本测试...")

        lifecycleScope.launch {
            try {
                val prompt = "请用JSON输出：{\"tool\":\"search_photos\",\"arguments\":{\"keyword\":\"毕业\"}}"

                withContext(Dispatchers.IO) {
                    wrapper.generate(prompt, maxTokens = 256, temperature = 0.1f) { token ->
                        launch(Dispatchers.Main) {
                            tvOutput.append(token)
                        }
                    }
                }
                tvOutput.append("\n✅ 纯文本测试完成！")
            } catch (e: Exception) {
                tvOutput.append("\n❌ 错误: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun testMultimodalPrompt() {
        val wrapper = llamaWrapper
        if (wrapper == null) {
            tvOutput.append("\n⚠️ 请先加载模型！")
            return
        }

        val imageBytes = selectedImageBytes
        if (imageBytes == null) {
            tvOutput.append("\n⚠️ 请先选择一张图片！")
            Toast.makeText(this, "请先点击「选择图片」", Toast.LENGTH_SHORT).show()
            return
        }

        tvOutput.append("\n📤 发送多模态分析请求...")

        lifecycleScope.launch {
            try {
                val prompt = "请描述这张图片的内容，用JSON格式输出：场景、主要物体、标签"

                withContext(Dispatchers.IO) {
                    wrapper.generateMultimodal(imageBytes, prompt, maxTokens = 256, temperature = 0.1f) { token ->
                        launch(Dispatchers.Main) {
                            tvOutput.append(token)
                        }
                    }
                }
                tvOutput.append("\n✅ 多模态测试完成！")
            } catch (e: Exception) {
                tvOutput.append("\n❌ 错误: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    companion object {
        private const val TAG = "MultimodalBench"
        const val EXTRA_BENCHMARK = "benchmark"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_RUNS = "runs"
        const val EXTRA_MAX_TOKENS = "maxTokens"
        const val EXTRA_GPU_LAYERS = "gpuLayers"

        /**
         * 把 AcceleratorPolicy 摆到指定状态：`reset` / `disable` / `exhaust` / `clear`。
         * 仅用于验证 AUTO 路径的降级分支，见 [applyNpuPolicyOverride]。
         */
        const val EXTRA_NPU_STATE = "npuState"

        /**
         * `true` 时改跑生产分析链路（真实相册照片），见 [runRealPhotoAnalysis]。
         * 需要与 `--ez benchmark true` 一起用。
         */
        const val EXTRA_PHOTO_ANALYSIS = "photoAnalysis"

        /** [EXTRA_PHOTO_ANALYSIS] 模式下分析几张照片。 */
        const val EXTRA_PHOTO_COUNT = "photoCount"

        /** 跳过候选列表开头的 N 张（用于选取不同样本）。 */
        const val EXTRA_PHOTO_SKIP = "photoSkip"

        /** 排除文件名含 screenshot/截图 的项，便于只看真实照片。 */
        const val EXTRA_PHOTO_EXCLUDE_SCREENSHOTS = "photoExcludeScreenshots"

        /**
         * 用给定提示词覆盖生产提示词，并作为**完整**提示词送入（不追加 JSON schema）。
         * 仅用于把"模型能力"与"提示词复杂度"分开验证。
         */
        const val EXTRA_PROMPT_OVERRIDE = "promptOverride"

        /** 合成图宽度，用于判别尺寸/长宽比的影响。 */
        const val EXTRA_IMAGE_W = "imageW"

        /** 合成图高度。 */
        const val EXTRA_IMAGE_H = "imageH"

        /**
         * 用指定文件作为测试图（绝对路径），替代合成场景图。
         *
         * 用于在**同一条受控路径**上对比"合成图 vs 真实照片"，从而区分差异来自图片
         * 内容还是代码路径。文件需放在 app 可读的位置（如 files/ 下，用 run-as 或 adb 推送）。
         */
        const val EXTRA_IMAGE_FILE = "imageFile"

        /**
         * 覆盖采样温度（float）。生产用 0.3，基准路径用 0f（贪心）能产出正确描述，
         * 用它把温度这个变量单独隔离出来。
         */
        const val EXTRA_TEMPERATURE = "temperature"

        /**
         * 让相册分析路径直接从该文件读图片字节（原字节，跳过 MediaStore 与重编码）。
         * 用于隔离"图片解码链路"与"后续代码路径"。
         */
        const val EXTRA_PHOTO_IMAGE_FILE = "photoImageFile"

        /** 跳过文本阶段，只跑多模态（用于隔离"先跑文本"是否为成功的必要条件）。 */
        const val EXTRA_SKIP_TEXT = "skipText"

        /**
         * 在每张真实照片之后追加一张合成场景图作为对照（同一提示词、同一代码路径），
         * 用于判别"模型处理不了真实照片内容"还是"相册路径有问题"。
         */
        const val EXTRA_SYNTHETIC_PROBE = "syntheticProbe"

        /**
         * 覆盖送入视觉塔前图片最长边的像素上限。
         *
         * 图像 token 数约为 (最长边/patch/merge)^2 * 长宽比。llama.cpp 对 Qwen-VL 的
         * 建议是至少 1024 个图像 token；384px 的图只有 84-196 个，远低于此。用它在
         * 设备上做分辨率对照，不必重编译。
         */
        const val EXTRA_IMAGE_SIDE = "imageSide"

        /**
         * flash attention 模式：0=自动（默认）1=强制关闭 2=强制开启。
         *
         * 用于判定 Vulkan 输出乱码是否由 FA 引起：实测 offload 0 层时输出完全正确、
         * 4 层起就乱码，所以问题出在 Vulkan 的算子实现上，而 FA 是首要嫌疑。
         */
        const val EXTRA_FLASH_ATTN = "flashAttn"

        /**
         * 逗号分隔的 ggml-Vulkan 规避开关后缀，例如 `DISABLE_FUSION,DISABLE_ASYNC`。
         *
         * 会自动补 `GGML_VK_` 前缀并置 1。用于定位"Vulkan offload 任意层就输出乱码"
         * 是哪个算子融合/coopmat/异步队列引起的 —— 这些开关只能在加载后端前 setenv。
         */
        /**
         * 用真实 App 的对象图（含 MobileCLIP 判定与 Room 落库）跑分析。
         * 见 [runAppPathAnalysis]。
         */
        const val EXTRA_APP_PATH = "appPath"

        /**
         * 让 [EXTRA_APP_PATH] 走相册批量入口（`analyzeAllPhotos`）而不是单张接口。
         * 流水线只在批量入口生效，所以验证它必须用这个。
         */
        const val EXTRA_APP_PATH_BATCH = "appPathBatch"

        /**
         * 流水线模式：连续分析多张，视觉塔（CPU）与 LLM（NPU）重叠。
         * 只改善批量总耗时，单张不变。见 [runPipelineAnalysis]。
         */
        const val EXTRA_PIPELINE = "pipeline"

        /**
         * CPU 线程数覆盖（-1 = 用生产值 4）。
         *
         * 单张分析里最长的一段是视觉塔的 CPU 编码，而它此前被硬编码成 4 线程，
         * 设备却有 8 核。这个开关用于量化"提高线程数能省多少"。
         */
        const val EXTRA_THREADS = "threads"

        /**
         * 视觉塔是否交给加速后端：1 = 交给（默认 0 = 纯 CPU）。
         *
         * 用于验证视觉塔能否上 NPU。失败会挂死（日志停在 `mtmd_helper_eval_chunks 开始`），
         * 所以是 opt-in 的实验开关。
         */
        const val EXTRA_MMPROJ_GPU = "mmprojGpu"

        /**
         * 视觉塔单独的 CPU 线程数（<=0 = 跟随 threads）。
         *
         * 视觉塔是单张分析里最长的一段（768px 下约 31 秒），且它用独立的 ggml
         * 线程池。两者一起提到 8 线程会挂死，但这不排除只给视觉塔加线程是安全的。
         */
        const val EXTRA_MMPROJ_THREADS = "mmprojThreads"

        /** KV cache 量化：0=f16（默认）1=q8_0 2=q4_0。 */
        const val EXTRA_KV_QUANT = "kvQuant"

        /** 是否隐藏 Vulkan 后端：1=隐藏（默认）0=保留。用于对照排查视觉塔死锁。 */
        const val EXTRA_HIDE_VULKAN = "hideVulkan"

        const val EXTRA_VK_FLAGS = "vkFlags"
    }
}
