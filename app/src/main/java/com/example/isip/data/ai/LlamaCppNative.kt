package com.example.isip.data.ai

import android.util.Log

/**
 * llama.cpp JNI 原生接口
 *
 * 提供与 C++ 层的交互
 */
class LlamaCppNative {

    /**
     * 初始化模型
     *
     * @param modelPath 模型文件路径
     * @param nThreads CPU 线程数
     * @param nCtx 上下文大小
     * @param nGpuLayers GPU 层数 (0 表示不使用 GPU)
     * @return 模型指针
     */
    external fun nativeInit(
        modelPath: String,
        nThreads: Int,
        nCtx: Int,
        nGpuLayers: Int,
        nAccelMode: Int
    ): Long

    /**
     * 生成文本
     *
     * @param modelPtr 模型指针
     * @param prompt 提示词
     * @param maxTokens 最大生成 token 数
     * @param temperature 采样温度
     * @return 生成的文本
     */
    external fun nativeGenerate(
        modelPtr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String

    /**
     * 释放模型资源
     *
     * @param modelPtr 模型指针
     */
    external fun nativeRelease(modelPtr: Long)

    /**
     * 获取版本信息
     *
     * @return 版本字符串
     */
    external fun nativeGetVersion(): String

    /**
     * 加载多模态投影层 (mmproj)
     *
     * @param mmprojPath mmproj GGUF 文件路径
     * @return 是否加载成功
     */
    external fun nativeLoadMmproj(mmprojPath: String): Boolean

    /**
     * 多模态生成（图片 + 文本）
     *
     * @param modelPtr 模型指针
     * @param imageData 图片字节数据
     * @param prompt 文本提示词
     * @param maxTokens 最大生成 token 数
     * @param temperature 采样温度
     * @return 生成的文本
     */
    external fun nativeGenerateMultimodal(
        modelPtr: Long,
        imageData: ByteArray,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String

    // ------------------------------------------------------------------
    // 流水线：把视觉塔编码与 LLM 推理解耦
    // ------------------------------------------------------------------
    //
    // 单张分析里视觉塔（CPU，约 6 秒）与 LLM（NPU，约 11 秒）串行，但两者用不同硬件、
    // 不同 ggml 上下文。连续分析多张时可以让第 N+1 张的编码与第 N 张的推理重叠。
    // **对单张分析没有帮助**（prefill 依赖视觉塔输出，无法重叠）。

    /**
     * 只做视觉塔编码（CPU），不碰 llama context，可在后台线程调用。
     *
     * @return 句柄，必须用 [nativeFreeEncoded] 释放；0 表示失败
     */
    external fun nativeEncodeImage(imageData: ByteArray, prompt: String): Long

    /**
     * 用 [nativeEncodeImage] 得到的 embeddings 做 prefill + 生成（NPU）。
     *
     * 内部会先清空 KV cache（与单张路径一致），所以它必须独占 llama context。
     */
    external fun nativeGenerateFromEncoded(
        modelPtr: Long,
        handle: Long,
        maxTokens: Int,
        temperature: Float
    ): String

    /** 释放 [nativeEncodeImage] 返回的句柄（内含 chunks 与 embeddings 拷贝）。 */
    external fun nativeFreeEncoded(handle: Long)

    /**
     * 描述本进程实际注册成功的 ggml 后端与可见设备。
     *
     * 用于回答"加速到底有没有生效"：llama.cpp 在没有加速后端时会静默走 CPU，
     * 所以必须把这个结果打进日志，而不是假定 nGpuLayers>0 就等于用了 GPU。
     *
     * @return 人类可读的后端清单，例如 "Vulkan: 1 device(s) [Adreno (TM) 830]"
     */
    external fun nativeDescribeBackends(): String

    /**
     * 把原生层日志同时写入 [path]。
     *
     * 这台设备（Android 16 / MIUI）不把应用自身的 logcat 输出交给 adb，只靠
     * logcat 无法调试原生层的挂死点；写文件后即使进程被强杀也能看到最后一步。
     */
    external fun nativeSetLogFile(path: String)

    /**
     * 从 App 原生库目录显式加载 ggml 后端模块。
     *
     * App 设不了环境变量，而 `ggml_backend_load_all()` 只搜编译期 `GGML_BACKEND_DIR`、
     * 可执行文件目录和 cwd（在 Android 上都不可控）；实测可靠通道是显式调用
     * `ggml_backend_load(path)`，所以这里把原生库目录整个交给它。
     *
     * @param backendDir 包含 libggml-<name>-*.so 的目录（通常是 applicationInfo.nativeLibraryDir）
     * @return 实际注册到的后端数量
     */
    external fun nativeLoadBackendsFrom(backendDir: String): Int

    /**
     * 按完整路径加载单个后端模块，失败时给出真实原因。
     *
     * 目录扫描在 Release 构建里是静默的（模块 dlopen 失败不会报错），
     * 所以排查阶段用这个显式入口。
     *
     * @return 1 表示成功，0 表示失败
     */
    external fun nativeLoadBackendByPath(path: String): Int

    /**
     * 设置 `ADSP_LIBRARY_PATH`，即 Hexagon DSP skel 的搜索路径。
     *
     * 必须在加载 NPU 后端模块**之前**调用：FastRPC 在第一次打开 CDSP 会话时读取该
     * 变量并据此解析 skel，之后再设已无意义。设不上的症状是会话创建失败：
     * `ggml-hex: failed to open session 0 : error 0x80000406`（AEE_EUNABLETOLOAD）。
     *
     * 该变量需要指向同时含有 `libggml-htp-v79.so` 与 `libggml-hexagon-0.so` 的目录，
     * 且目录必须真实存在于文件系统上（守护进程读不到 APK 内的压缩条目）。
     *
     * @param path 搜索路径；传空串表示清除
     * @return 1 表示成功
     */
    external fun nativeSetAdspLibraryPath(path: String): Int

    /** 回读当前 `ADSP_LIBRARY_PATH`，用于确认真正生效的值。 */
    external fun nativeGetAdspLibraryPath(): String

    /**
     * 设置 flash attention 模式：0=自动（llama.cpp 默认）1=强制关闭 2=强制开启。
     *
     * 必须在 [nativeInit] 之前调用。存在理由：Vulkan 上只要 offload 任意层（实测
     * 4/25 层起）输出就变成乱码，0 层时完全正确 —— 错在 Vulkan 的算子实现。FA 是
     * 该路径上嫌疑最大的一项，需要能单独关掉做对照。
     *
     * @return 实际生效的模式值
     */
    external fun nativeSetFlashAttnMode(mode: Int): Int

    /**
     * 设置任意环境变量，必须在加载后端模块 / 建上下文之前调用。
     *
     * ggml 各后端把调优与规避开关都放在环境变量里（Vulkan 有 30 多个），而 App
     * 进程设不了环境变量，只能通过这里 setenv。ADSP_LIBRARY_PATH 走的是同一条通道。
     *
     * @param value 传 null 表示清除该变量
     * @return 1 表示成功
     */
    external fun nativeSetEnv(name: String, value: String?): Int

    /**
     * 视觉塔（mmproj）是否交给加速后端，必须在加载 mmproj 之前调用。
     *
     * 默认 0（纯 CPU）。设 1 用于验证"视觉塔能否上 NPU"—— 它是单张分析里最长的
     * 一段（约 34 秒 / 60 秒）。风险是可能挂死：失败时原生日志会停在
     * `mtmd_helper_eval_chunks 开始` 且 CPU 占用为 0。
     *
     * @return 实际生效的值
     */
    external fun nativeSetMmprojUseGpu(useGpu: Int): Int

    /**
     * 视觉塔单独使用的 CPU 线程数（<=0 表示跟随 [nativeInit] 的 nThreads）。
     *
     * 必须在加载 mmproj 之前调用。视觉塔是独立的 mtmd 上下文与线程池，
     * 而它是单张分析里最长的一段，所以值得与 LLM 的线程数分开调。
     *
     * @return 实际生效的值
     */
    external fun nativeSetMmprojThreads(nThreads: Int): Int

    /**
     * KV cache 量化：0 = 默认 f16，1 = q8_0，2 = q4_0。必须在 [nativeInit] 之前调用。
     *
     * decode 约占单张分析的三分之一，每步都要读写整个 KV cache，对内存带宽敏感；
     * 量化能减小带宽与占用，代价是精度。
     */
    external fun nativeSetKvQuant(mode: Int): Int

    companion object {
        private const val TAG = "LlamaCppNative"

        init {
            try {
                // libllama-jni.so 是自包含的：llama.cpp + ggml + Vulkan 后端全部静态链接在内。
                // 它自带那套 libggml-base/cpu/llama.so 曾放在 jniLibs 里，但那会造成进程内
                // 出现两份 ggml 实现（各自有独立的注册表状态），从而让后端注册互相看不见。
                System.loadLibrary("llama-jni")
                Log.i(TAG, "✅ llama.cpp JNI 库加载成功")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ JNI 库加载失败", e)
                throw RuntimeException("无法加载 llama.cpp JNI 库", e)
            }
        }
    }
}
