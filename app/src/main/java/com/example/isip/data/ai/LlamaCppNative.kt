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
        nGpuLayers: Int
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

    companion object {
        private const val TAG = "LlamaCppNative"

        init {
            try {
                // 加载依赖库（按依赖顺序）
                System.loadLibrary("ggml-base")
                System.loadLibrary("ggml-cpu")
                System.loadLibrary("ggml")
                System.loadLibrary("llama")
                System.loadLibrary("llama-jni")
                Log.i(TAG, "✅ llama.cpp JNI 库加载成功")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ JNI 库加载失败", e)
                throw RuntimeException("无法加载 llama.cpp JNI 库", e)
            }
        }
    }
}
