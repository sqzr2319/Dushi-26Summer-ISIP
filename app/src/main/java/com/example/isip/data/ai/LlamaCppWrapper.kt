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

    /** 获取最后一次错误信息 */
    fun getLastError(): String? = lastError

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
            val nGpuLayers = if (config?.useGPU == true) 999 else 0

            modelPtr = native.nativeInit(
                modelPath = modelFile.absolutePath,
                nThreads = nThreads,
                nCtx = nCtx,
                nGpuLayers = nGpuLayers
            )

            if (modelPtr == 0L) {
                lastError = "nativeInit 返回空指针，模型加载失败"
                Log.e(TAG, lastError!!)
                return false
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

            true
        } catch (e: Exception) {
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
        try {
            Log.d(TAG, "生成文本，提示词长度: ${prompt.length}")
            val result = native.nativeGenerate(
                modelPtr = modelPtr,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature
            )
            Log.d(TAG, "生成完成，响应长度: ${result.length}")
            callback(result)
        } catch (e: Exception) {
            Log.e(TAG, "文本生成失败", e)
            callback("错误: ${e.message}")
        }
    }

    /**
     * 多模态生成（新 API）
     */
    fun generateMultimodal(
        imageData: ByteArray,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        callback: (String) -> Unit
    ) {
        checkInitialized()
        try {
            Log.d(TAG, "多模态生成，图像大小: ${imageData.size} bytes")

            // 判断是否已加载 mmproj：如果 nativeLoadMmproj 已成功，g_has_mmproj=true
            // 调用真正的多模态 JNI
            val result = native.nativeGenerateMultimodal(
                modelPtr = modelPtr,
                imageData = imageData,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature
            )
            Log.d(TAG, "多模态生成完成，响应长度: ${result.length}")
            callback(result)
        } catch (e: Exception) {
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
            modelPtr = native.nativeInit(
                modelPath = modelFile.absolutePath,
                nThreads = cfg.numThreads,
                nCtx = cfg.contextSize,
                nGpuLayers = if (cfg.useGPU) 999 else 0
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
