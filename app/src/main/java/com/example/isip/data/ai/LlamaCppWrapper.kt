package com.example.isip.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * llama.cpp wrapper for Android
 *
 * 使用 JNI 调用 llama.cpp 原生库
 */
class LlamaCppWrapper(
    private val context: Context? = null,
    private val config: ModelConfig? = null
) {
    private val native = LlamaCppNative()
    private var modelPtr: Long = 0
    private var isInitialized = false
    private var lastError: String? = null

    /**
     * NPU 的启用/降级决策。为 null 时退化为旧行为（只认 config.enableHexagonNpu）。
     */
    private val policy: AcceleratorPolicy? =
        context?.let { AcceleratorPolicy(it) }

    /**
     * 本次加载是否真的尝试了 NPU。
     *
     * 用来决定"记账"的归属：只有尝试过 NPU 才应该把失败记到 NPU 头上 —— 否则
     * 纯 CPU 模式下的一次 OOM 会把 NPU 误判为不可用。
     */
    private var npuAttempted = false

    /** 加速后端是否已注册过。让 [loadModel] 的自动注册保持幂等。 */
    private var backendsLoaded = false

    /** 获取最后一次错误信息 */
    fun getLastError(): String? = lastError

    /**
     * 本进程实际注册成功的 ggml 后端与可见设备。
     *
     * 这是判断"加速有没有生效"的唯一可信来源：llama.cpp 在加速后端缺失时会静默
     * 走 CPU，因此不能只看 gpuLayerCount。
     */
    fun describeBackends(): String = try {
        native.nativeDescribeBackends()
    } catch (e: Exception) {
        "unavailable: ${e.message}"
    }

    /**
     * 加速策略的人话描述 + 原生实际生效后端，供设置页如实展示。
     */
    fun describeAcceleration(): String {
        val p = policy?.describe() ?: "未配置策略"
        return "$p；实际后端: ${describeBackends()}"
    }

    /**
     * 本次加载是否真正尝试了 NPU。
     */
    fun wasNpuAttempted(): Boolean = npuAttempted

    /**
     * 原生报告里 HTP 是否带有可用设备。
     *
     * 用于在推理成功路径上判断"这次是不是真的跑在 NPU 上"。
     */
    fun isNpuActive(): Boolean = try {
        val r = native.nativeDescribeBackends()
        r.contains("HTP") && !r.contains("HTP: 0 device")
    } catch (e: Exception) {
        false
    }

    /** NPU 推理开始前调用（置跨进程标记，用于检测崩溃）。 */
    fun beginNpuInferenceGuard() {
        if (npuAttempted) policy?.markInferenceStarted()
    }

    /** NPU 推理正常结束后调用（清标记并记账为成功）。 */
    fun endNpuInferenceGuard() {
        if (npuAttempted) {
            // 走到这里说明一次真实推理完整返回了 —— 这才是"NPU 确实可用"的证据，
            // 也才应该清零失败计数。
            policy?.recordNpuSuccess()
            policy?.markInferenceCompleted()
        }
    }

    /**
     * 在**开始加载模型**时置位崩溃标记。
     *
     * 为什么不能只靠 [beginNpuInferenceGuard]：实测有一次生产路径在**模型加载阶段**
     * 就挂死了（0.0% CPU、进程存活、无 tombstone），此时还没走到生成，生成前的标记
     * 根本没置上，于是下次启动检测不到、策略永远不降级 —— 用户会每次都卡在同一个坑里。
     *
     * 因此加载一开始就置位，加载成功且第一次推理走完后才清除（见 [loadModel] 与
     * [endNpuInferenceGuard]）。中间任何一步以进程死亡收场，下次启动都会被识别。
     */
    fun beginNpuLoadGuard() {
        if (npuAttempted) policy?.markInferenceStarted()
    }

    /**
     * 把原生层日志同时写入 files/bench/native.log。
     *
     * 设备不把应用 logcat 交给 adb，所以调试原生挂死点时必须以文件为准。
     */
    fun enableNativeFileLog(): String? = try {
        val dir = java.io.File(context?.filesDir ?: return null, "bench").apply { mkdirs() }
        val f = java.io.File(dir, "native.log")
        native.nativeSetLogFile(f.absolutePath)
        f.absolutePath
    } catch (e: Exception) {
        Log.e(TAG, "启用原生日志失败", e)
        null
    }

    /**
     * 设置原生侧环境变量（必须在加载后端模块之前）。
     *
     * ggml 各后端把规避/调优开关全放在环境变量里，而 App 进程设不了环境变量，
     * 只能通过 JNI setenv。用于定位 Vulkan 输出乱码这类后端级问题。
     */
    fun setNativeEnv(name: String, value: String?): Int = try {
        native.nativeSetEnv(name, value)
    } catch (e: Exception) {
        Log.e(TAG, "设置原生环境变量失败: $name", e)
        0
    }

    /**
     * 视觉塔是否交给加速后端（实验开关）。见 [LlamaCppNative.nativeSetMmprojUseGpu]。
     */
    fun setMmprojUseGpu(useGpu: Boolean): Int = try {
        native.nativeSetMmprojUseGpu(if (useGpu) 1 else 0)
    } catch (e: Exception) {
        Log.e(TAG, "设置 mmproj use_gpu 失败", e)
        0
    }

    /**
     * 视觉塔单独的线程数（<=0 表示跟随 ModelConfig.numThreads）。
     */
    fun setMmprojThreads(nThreads: Int): Int = try {
        native.nativeSetMmprojThreads(nThreads)
    } catch (e: Exception) {
        Log.e(TAG, "设置 mmproj 线程数失败", e)
        0
    }

    /**
     * KV cache 量化：0=f16（默认）1=q8_0 2=q4_0。
     */
    fun setKvQuant(mode: Int): Int = try {
        native.nativeSetKvQuant(mode)
    } catch (e: Exception) {
        Log.e(TAG, "设置 KV 量化失败", e)
        0
    }

    /**
     * 从 App 原生库目录加载 ggml 后端模块（Vulkan / Hexagon NPU / CPU）。
     *
     * 必须在 [loadModel] 之前调用：ggml 注册表为空时加载模型会直接失败。
     * App 无法设置环境变量，因此走显式加载而不是 ggml_backend_load_all()。
     *
     * 这里有个 Android 特有的坑：从 Android 10 起系统默认**不把 .so 解压**到
     * `nativeLibraryDir`（直接从 APK 映射），所以那个目录通常是空的，目录扫描什么也
     * 找不到。办法是自己把模块从 APK 解到私有目录，再按完整路径显式加载。
     *
     * @return 成功注册的后端数量
     */
    fun loadAcceleratorBackends(): Int = try {
        val ctx = context
        if (ctx == null) {
            Log.w(TAG, "无 Context，跳过后端加载")
            0
        } else {
            // 先看上一次 NPU 运行是不是以进程死亡告终。必须在任何加载动作之前做，
            // 因为记录要先于决策生效。
            policy?.checkForPreviousCrash()

            val nativeDir = ctx.applicationInfo.nativeLibraryDir

            // 优先从 nativeLibraryDir 加载。FastRPC 守护进程解析 DSP skel 时以
            // *客户端自身的原生库目录*作为搜索根，而 libggml-htp-v79.so 是那个目录里
            // 的一个文件。模块若从别处加载，守护进程就找不到与它同名的 skel。
            // 该目录有内容的前提是 build.gradle.kts 开了 jniLibs.useLegacyPackaging。
            val modules = listOf("libggml-hexagon-0.so", "libggml-htp-v79.so")
            val outDir = java.io.File(ctx.filesDir, "backends").apply { mkdirs() }

            var modulePath: String? = null
            for (name in modules) {
                val inNativeDir = java.io.File(nativeDir, name)
                if (inNativeDir.isFile && inNativeDir.length() > 0) {
                    if (name == "libggml-hexagon-0.so") modulePath = inNativeDir.absolutePath
                    continue
                }
                // 回退：设备未解压 .so 时从 APK 提取到私有目录
                val target = java.io.File(outDir, name)
                val apkSize = apkEntrySize(ctx, "lib/arm64-v8a/$name")
                if (!target.isFile || (apkSize > 0 && target.length() != apkSize)) {
                    extractFromApk(ctx, "lib/arm64-v8a/$name", target)
                }
                if (name == "libggml-hexagon-0.so" && target.isFile) modulePath = target.absolutePath
            }

            // ------------------------------------------------------------------
            // 是否尝试 NPU：由 AcceleratorPolicy 决定，而不是只看 config
            // ------------------------------------------------------------------
            // 相册分析的默认配置是 backend=AUTO。AUTO 在原生侧会优先挑 HTP0，
            // 但前提是 hexagon 模块已经被加载 —— 而模块加载此前只由
            // config.enableHexagonNpu（默认 false）控制，所以日常使用根本吃不到
            // NPU。这里把决策交给 AcceleratorPolicy：默认尝试，连续失败后自动
            // 降级并持久化，用户也可在设置页关掉。
            //
            // config.enableHexagonNpu=true 仍可强制尝试（基准测试用），不受策略约束。
            val forceNpu = config?.enableHexagonNpu == true
            val policyAllows = policy?.shouldAttemptNpu() ?: false
            val wantNpu = forceNpu || policyAllows
            npuAttempted = wantNpu
            if (wantNpu) {
                Log.i(
                    TAG,
                    "NPU 决策: 尝试启用（force=$forceNpu policy=$policyAllows，" +
                        "策略状态=${policy?.describe() ?: "无策略"}）"
                )
            } else {
                Log.i(
                    TAG,
                    "NPU 决策: 跳过（策略状态=${policy?.describe() ?: "无策略"}，config=$forceNpu）"
                )
            }

            // ------------------------------------------------------------------
            // ADSP_LIBRARY_PATH —— 顺序在这里是硬要求
            // ------------------------------------------------------------------
            // Hexagon 的 DSP skel（libggml-htp-v79.so）由 FastRPC 守护进程在 CDSP 上
            // 加载，守护进程按**文件名**在 ADSP_LIBRARY_PATH 里查找它。App 通过 shell
            // 设不了这个变量，所以必须在本进程内 setenv。
            //
            // 必须在任何一次 CDSP 会话创建之前完成 —— 也就是说，必须早于下面第一次
            // 加载 hexagon 模块。此前的实现把 setenv 放在 nativeLoadBackendsFrom()
            // 里，而那个调用排在 nativeLoadBackendByPath() 之后，于是设置永远晚一步，
            // 会话创建以 0x80000406 (AEE_EUNABLETOLOAD) 失败。
            //
            // 目录顺序有讲究：skel 既可能在 nativeLibraryDir（APK 解压态），也可能在
            // 上面的回退目录里，两个都给，冒号分隔。
            val skelDirs = buildList {
                add(nativeDir)
                if (modulePath != null && modulePath.startsWith(outDir.absolutePath)) {
                    add(outDir.absolutePath)
                }
            }.distinct().joinToString(":")

            if (wantNpu) {
                native.nativeSetAdspLibraryPath(skelDirs)
                Log.i(TAG, "ADSP_LIBRARY_PATH=${native.nativeGetAdspLibraryPath()}")
            }

            val n = if (modulePath != null && wantNpu) {
                Log.i(TAG, "加载 NPU 后端模块: $modulePath")
                native.nativeLoadBackendByPath(modulePath)
            } else if (modulePath != null) {
                Log.i(TAG, "跳过 NPU 后端: $modulePath")
                0
            } else {
                Log.w(TAG, "未找到 libggml-hexagon-0.so")
                0
            }

            // 顺带扫一遍原生库目录，让静态注册的后端（Vulkan/CPU）也在册
            native.nativeLoadBackendsFrom(nativeDir)
            backendsLoaded = true

            Log.i(TAG, "后端详情: ${describeBackends()}")
            n
        }
    } catch (e: Exception) {
        Log.e(TAG, "加载 ggml 后端失败", e)
        0
    }

    /** 返回 APK 内某个条目的解压后大小；条目不存在时返回 -1。 */
    private fun apkEntrySize(ctx: android.content.Context, entry: String): Long = try {
        java.util.zip.ZipFile(ctx.applicationInfo.sourceDir).use { zip ->
            zip.getEntry(entry)?.size ?: -1L
        }
    } catch (e: Exception) {
        -1L
    }

    /**
     * 从已安装的 APK 中提取一个共享库条目到 [target]。
     *
     * 用 java.util.zip.ZipFile 读 APK 本身：原生库不是 assets，读不到
     * （它只在 zip 里以 STORED/DEFLATED 条目存在）。这样无需开启 legacy packaging
     * 就能拿到模块文件，代价是私有目录多一份副本。
     */
    private fun extractFromApk(ctx: android.content.Context, entry: String, target: java.io.File) {
        try {
            java.util.zip.ZipFile(ctx.applicationInfo.sourceDir).use { zip ->
                val e = zip.getEntry(entry)
                if (e == null) {
                    Log.w(TAG, "APK 中没有条目 $entry")
                    return
                }
                zip.getInputStream(e).use { input ->
                    java.io.FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
            Log.i(TAG, "已从 APK 提取 $entry -> ${target.absolutePath} (${target.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "提取 $entry 失败: ${e.message}")
        }
    }

    /**
     * 将外部存储的文件复制到应用私有目录
     */
    private fun copyToInternalStorage(sourcePath: String): String? {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return null

        val ctx = context ?: return null
        val destFile = File(ctx.filesDir, "models/${sourceFile.name}")
        destFile.parentFile?.mkdirs()

        return try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "已复制模型到内部存储: ${destFile.absolutePath}")
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "复制模型文件失败", e)
            null
        }
    }

    /**
     * 加载模型（新 API，供 MultimodalTestActivity 使用）
     *
     * @param modelPath GGUF 模型文件路径
     * @param mmprojPath 多模态投影文件路径（可选）
     * @return 是否加载成功
     */
    fun loadModel(modelPath: String, mmprojPath: String? = null): Boolean {
        // 原生日志写文件 —— 在这里默认开启，而不是等调用方记得开。
        //
        // 理由：这台设备（Android 16 / MIUI）不把应用自身的 logcat 交给 adb，一旦
        // 原生层挂死（0.0% CPU、进程存活、无 tombstone）就完全无迹可查。实测生产路径
        // 正因为在加载期挂死且没开文件日志，导致排查时拿不到任何信息。代价是每次
        // 加载多写几十 KB，换可诊断性，值得。
        enableNativeFileLog()

        // 默认把 Vulkan 后端对 ggml 隐藏起来（空设备列表）。
        //
        // 两个实测理由，任一都足够：
        //  1. **算错。** Vulkan 在这台 SM8750 上输出结构完整、内容乱码的结果，
        //     0 层卸载正确、4 层起全乱（见 AccelerationBackend.GPU_VULKAN）。
        //  2. **崩掉视觉塔。** 让视觉塔用加速后端时有两次失败：
        //     `mtmd_context_params.use_gpu = true` 时日志说 `CLIP using HTP0 backend`，
        //     但随后进程 SIGABRT：`vk::Device::createComputePipeline: ErrorUnknown`。
        //     原因是 clip/mtmd **不遵守** nativeInit 里给主模型设的设备白名单，
        //     它能看到全部已注册后端，于是有算子落到 Vulkan 上。
        //     隐藏 Vulkan 之后 clip 就只剩 HTP0 可选，这正是我们要的。
        //
        // 显式选 GPU 后端时保留它（那是专门用来复现 Vulkan 问题的诊断路径）。
        val wantsVulkan = config?.backend == AccelerationBackend.GPU_VULKAN
        if (!wantsVulkan && config?.hideVulkan != false) {
            native.nativeSetEnv("GGML_VK_VISIBLE_DEVICES", "")
            Log.i(TAG, "已隐藏 Vulkan 后端（GGML_VK_VISIBLE_DEVICES 置空）")
        }

        // 注册加速后端 —— 放在这里而不是交给调用方，是因为**生产路径曾经漏掉过这一步**。
        //
        // 相册分析经由 QwenInferenceEngine → LlamaCppWrapper 直接调 loadModel()，从不
        // 显式调 loadAcceleratorBackends()；而 nativeInit() 内部的 ggml_backend_load_all()
        // 只找得到静态链接进 libllama-jni.so 的 Vulkan/CPU，找不到以 .so 分发的 hexagon
        // 模块。结果就是 HTP0 从未注册、npuEnabled 明明为 true 却静默跑在 GPU 上。
        //
        // 幂等：已加载过就跳过，避免重复 dlopen 出第二份注册表。
        if (!backendsLoaded) {
            loadAcceleratorBackends()
        }

        // 加载期也要能被崩溃检测覆盖，见 beginNpuLoadGuard 的说明。
        beginNpuLoadGuard()
        return try {
            Log.d(TAG, "开始加载 GGUF 模型: $modelPath")

            var modelFile = File(modelPath)
            var actualModelPath = modelPath

            // 如果外部文件不可访问，尝试复制到内部存储
            if (!modelFile.exists() || !modelFile.canRead()) {
                Log.w(TAG, "外部路径不可读，尝试复制到内部存储...")
                val internalPath = copyToInternalStorage(modelPath)
                if (internalPath != null) {
                    actualModelPath = internalPath
                    modelFile = File(internalPath)
                } else {
                    lastError = "模型文件不存在或无法访问: $modelPath"
                    Log.e(TAG, lastError!!)
                    return false
                }
            }

            // 如果 mmproj 文件也需要复制
            var actualMmprojPath = mmprojPath
            if (mmprojPath != null) {
                val mmprojFile = File(mmprojPath)
                if (!mmprojFile.exists() || !mmprojFile.canRead()) {
                    Log.w(TAG, "mmproj 外部路径不可读，尝试复制到内部存储...")
                    val internalMmproj = copyToInternalStorage(mmprojPath)
                    if (internalMmproj != null) {
                        actualMmprojPath = internalMmproj
                    }
                }
            }

            val nThreads = config?.numThreads ?: 4
            val nCtx = config?.contextSize ?: 2048
            val nGpuLayers = config?.gpuLayerCount ?: 0

            Log.i(TAG, "加载配置: threads=$nThreads ctx=$nCtx gpuLayers=$nGpuLayers backend=${config?.backend}")

            val loadStart = System.currentTimeMillis()
            native.nativeSetFlashAttnMode(config?.flashAttnMode ?: 0)
            // 必须在 nativeLoadMmproj 之前：它决定视觉塔建在哪个后端上。
            native.nativeSetMmprojUseGpu(if (config?.mmprojUseGpu == true) 1 else 0)
            // 视觉塔线程数与 LLM 分开：它是单张分析里最长的一段，而两者用的是
            // 各自独立的 ggml 线程池。
            native.nativeSetMmprojThreads(config?.mmprojThreads ?: -1)
            native.nativeSetKvQuant(config?.kvQuant ?: 0)
            modelPtr = native.nativeInit(
                modelPath = modelFile.absolutePath,
                nThreads = nThreads,
                nCtx = nCtx,
                nGpuLayers = nGpuLayers,
                nAccelMode = config?.nativeAccelMode ?: 0
            )

            if (modelPtr == 0L) {
                lastError = "nativeInit 返回空指针，模型加载失败"
                Log.e(TAG, lastError!!)
                return false
            }

            Log.i(TAG, "模型加载耗时: ${System.currentTimeMillis() - loadStart} ms")
            val backendReport = native.nativeDescribeBackends()
            Log.i(TAG, "实际生效的后端: $backendReport")

            // ------------------------------------------------------------------
            // NPU 记账
            // ------------------------------------------------------------------
            // 只有真的尝试过 NPU 才记账，避免纯 CPU 模式下的偶发失败被算到 NPU 头上。
            //
            // 注意这里**不**记成功。注册表里有带设备的 HTP 只是必要条件，不是充分
            // 条件 —— 实测生产路径曾在加载期直接挂死。成功要等一次真实推理正常返回
            // 才认（见 endNpuInferenceGuard），否则会把"会话其实不可用"误判为可用，
            // 失败计数永远清零，降级机制形同虚设。
            if (npuAttempted) {
                val npuRegistered = backendReport.contains("HTP") &&
                    !backendReport.contains("HTP: 0 device")
                if (npuRegistered) {
                    Log.i(TAG, "NPU 已注册并有可用设备（待首次推理验证）")
                } else {
                    policy?.recordNpuFailure("HTP 后端未注册出可用设备（报告: $backendReport）")
                }
            }

            isInitialized = true
            val version = native.nativeGetVersion()
            Log.i(TAG, "✅ GGUF 模型加载成功")
            Log.i(TAG, "版本: $version")

            if (mmprojPath != null) {
                val mmprojFile = File(mmprojPath)
                if (mmprojFile.exists() && mmprojFile.canRead()) {
                    Log.i(TAG, "加载多模态投影层: $mmprojPath")
                    val mmprojOk = native.nativeLoadMmproj(mmprojPath)
                    if (mmprojOk) {
                        Log.i(TAG, "✅ 多模态投影层加载成功")
                    } else {
                        Log.w(TAG, "⚠️ 多模态投影层加载失败，仅支持文本模式")
                    }
                } else {
                    Log.w(TAG, "mmproj 文件不可访问: $mmprojPath")
                }
            }

            // 注意：这里**不清**崩溃标记。加载成功只说明走到了这一步，真正的考验是
            // 第一次推理（NPU 的会话可能推迟到首次计算才真正建立）。标记要留到那次
            // 推理正常返回后才清，见 endNpuInferenceGuard()。
            true
        } catch (e: Exception) {
            // 抛异常而非进程死亡，属于"这次没成功但进程还活着"：标记在这里清掉，
            // 失败另记一笔，避免下次启动把普通异常误判成崩溃。
            if (npuAttempted) policy?.recordNpuFailure("模型加载抛异常: ${e.message}")
            endNpuInferenceGuard()
            lastError = "模型加载异常: ${e.message}"
            Log.e(TAG, lastError!!, e)
            false
        }
    }

    /**
     * 带回调的文本生成（新 API）
     */
    fun generate(prompt: String, maxTokens: Int, temperature: Float, callback: (String) -> Unit) {
        checkInitialized()
        // NPU 崩溃会直接杀进程，catch 抓不到，所以用跨进程标记反推（见 AcceleratorPolicy）。
        beginNpuInferenceGuard()
        try {
            Log.d(TAG, "生成文本，提示词长度: ${prompt.length}")
            val start = System.currentTimeMillis()
            val result = native.nativeGenerate(
                modelPtr = modelPtr,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature
            )
            Log.i(TAG, "文本生成耗时: ${System.currentTimeMillis() - start} ms, 响应长度: ${result.length}")
            endNpuInferenceGuard()
            callback(result)
        } catch (e: Exception) {
            // 异常不等于进程崩溃，但仍说明这次 NPU 推理没成功，如实记账
            if (npuAttempted) policy?.recordNpuFailure("文本生成抛异常: ${e.message}")
            endNpuInferenceGuard()
            Log.e(TAG, "文本生成失败", e)
            callback("错误: ${e.message}")
        }
    }

    /**
     * 多模态生成（新 API）
     */
    /**
     * 流水线：只做视觉塔编码（CPU）。可在后台线程与 [generateFromEncoded] 并行调用。
     *
     * 与 [generateMultimodal] 的区别：它**不碰 llama context**，所以不会与正在
     * 推理的线程争用 KV cache。这就是流水线能成立的原因。
     *
     * @return 句柄（失败为 0），必须交给 [freeEncoded] 释放
     */
    fun encodeImage(imageData: ByteArray, prompt: String): Long =
        native.nativeEncodeImage(imageData, prompt)

    /**
     * 流水线：用预先编码的 embeddings 做 prefill + 生成（NPU）。
     *
     * 内部先清空 KV cache，因此必须独占 llama context —— 同一时刻只能有一个调用，
     * 但可以与 [encodeImage] 并行（那一个只用 clip）。
     */
    fun generateFromEncoded(handle: Long, maxTokens: Int, temperature: Float): String {
        checkInitialized()
        beginNpuInferenceGuard()
        try {
            val result = native.nativeGenerateFromEncoded(modelPtr, handle, maxTokens, temperature)
            endNpuInferenceGuard()
            return result
        } catch (e: Exception) {
            if (npuAttempted) policy?.recordNpuFailure("流水线生成抛异常: ${e.message}")
            endNpuInferenceGuard()
            throw e
        }
    }

    /** 释放 [encodeImage] 返回的句柄（内含 chunks 与 embeddings 拷贝）。 */
    fun freeEncoded(handle: Long) {
        if (handle != 0L) native.nativeFreeEncoded(handle)
    }

    fun generateMultimodal(
        imageData: ByteArray,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        callback: (String) -> Unit
    ) {
        checkInitialized()
        beginNpuInferenceGuard()
        try {
            Log.d(TAG, "多模态生成，图像大小: ${imageData.size} bytes")

            // 判断是否已加载 mmproj：如果 nativeLoadMmproj 已成功，g_has_mmproj=true
            // 调用真正的多模态 JNI
            val start = System.currentTimeMillis()
            val result = native.nativeGenerateMultimodal(
                modelPtr = modelPtr,
                imageData = imageData,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature
            )
            Log.i(TAG, "多模态生成耗时: ${System.currentTimeMillis() - start} ms, 响应长度: ${result.length}")
            endNpuInferenceGuard()
            callback(result)
        } catch (e: Exception) {
            if (npuAttempted) policy?.recordNpuFailure("多模态生成抛异常: ${e.message}")
            endNpuInferenceGuard()
            Log.e(TAG, "多模态生成失败", e)
            callback("错误: ${e.message}")
        }
    }

    /**
     * 卸载模型（新 API）
     */
    fun unload() {
        release()
    }

    /**
     * 初始化模型（旧 API，供 QwenInferenceEngine 使用）
     */
    fun initialize(modelPath: String, mmProjPath: String?) {
        try {
            Log.d(TAG, "开始加载 GGUF 模型: $modelPath")

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                throw ModelInitializationException("模型文件不存在: $modelPath")
            }

            // 调用 JNI 初始化
            val cfg = config ?: throw ModelInitializationException("ModelConfig is required for initialize()")
            native.nativeSetFlashAttnMode(cfg.flashAttnMode)
            modelPtr = native.nativeInit(
                modelPath = modelFile.absolutePath,
                nThreads = cfg.numThreads,
                nCtx = cfg.contextSize,
                nGpuLayers = cfg.gpuLayerCount,
                nAccelMode = cfg.nativeAccelMode
            )

            if (modelPtr == 0L) {
                throw ModelInitializationException("模型初始化失败")
            }

            isInitialized = true

            // 获取版本信息
            val version = native.nativeGetVersion()
            Log.i(TAG, "✅ GGUF 模型加载成功")
            Log.i(TAG, "版本: $version")
            Log.i(TAG, "模型指针: 0x${modelPtr.toString(16)}")

            if (mmProjPath != null) {
                Log.w(TAG, "⚠️ 多模态投影层支持需要额外实现")
            }

        } catch (e: Exception) {
            Log.e(TAG, "模型加载失败", e)
            throw ModelInitializationException("无法加载模型: ${e.message}", e)
        }
    }

    /**
     * 文本生成
     */
    fun generate(prompt: String, maxTokens: Int, temperature: Float): String {
        checkInitialized()

        try {
            Log.d(TAG, "生成文本，提示词长度: ${prompt.length}")

            val result = native.nativeGenerate(
                modelPtr = modelPtr,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature
            )

            Log.d(TAG, "生成完成，响应长度: ${result.length}")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "文本生成失败", e)
            throw InferenceException("生成失败: ${e.message}", e)
        }
    }

    /**
     * 带图像的多模态生成
     */
    fun generateWithImage(bitmap: Bitmap, prompt: String, maxTokens: Int, temperature: Float): String {
        checkInitialized()

        val width = bitmap.width
        val height = bitmap.height
        val aspectRatio = width.toFloat() / height.toFloat()

        val orientation = when {
            aspectRatio > 1.5f -> "横向"
            aspectRatio < 0.67f -> "竖向"
            else -> "方形"
        }

        Log.w(TAG, "多模态支持需要额外实现，当前使用图像信息增强 prompt")
        Log.d(TAG, "图像: ${width}x${height}")

        // 将图像信息添加到 prompt
        val enhancedPrompt = """
            图像信息：尺寸 ${width}x${height}，方向 ${orientation}

            $prompt
        """.trimIndent()

        return generate(enhancedPrompt, maxTokens, temperature)
    }

    private fun checkInitialized() {
        if (!isInitialized || modelPtr == 0L) {
            throw IllegalStateException("模型未初始化")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        if (modelPtr != 0L) {
            try {
                native.nativeRelease(modelPtr)
                Log.d(TAG, "模型资源已释放")
            } catch (e: Exception) {
                Log.e(TAG, "释放资源时出错", e)
            } finally {
                modelPtr = 0
                isInitialized = false
            }
        }
    }

    companion object {
        private const val TAG = "LlamaCppWrapper"
    }
}
