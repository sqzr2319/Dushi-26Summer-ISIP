package com.example.isip.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * llama.cpp wrapper for Android
 *
 * 使用 JNI 调用 llama.cpp 原生库
 */
class LlamaCppWrapper(
    private val context: Context,
    private val config: ModelConfig
) {
    private val native = LlamaCppNative()
    private var modelPtr: Long = 0
    private var isInitialized = false

    /**
     * 初始化模型
     */
    fun initialize(modelPath: String, mmProjPath: String?) {
        try {
            Log.d(TAG, "开始加载 GGUF 模型: $modelPath")

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                throw ModelInitializationException("模型文件不存在: $modelPath")
            }

            // 调用 JNI 初始化
            modelPtr = native.nativeInit(
                modelPath = modelFile.absolutePath,
                nThreads = config.numThreads,
                nCtx = config.contextSize,
                nGpuLayers = if (config.useGPU) 999 else 0
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
