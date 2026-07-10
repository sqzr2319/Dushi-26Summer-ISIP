package com.example.photoagent.ai

import android.graphics.Bitmap

/**
 * 推理引擎接口
 * 所有大模型推理调用通过此接口
 */
interface InferenceEngine {

    /**
     * 初始化模型
     * @param config 模型配置
     * @return 是否初始化成功
     */
    suspend fun initialize(config: ModelConfig): Boolean

    /**
     * 执行推理（文本/图片输入）
     * @param prompt 提示词
     * @param image 图片（可选，多模态输入）
     * @param maxTokens 最大输出Token数
     * @param temperature 温度参数
     * @return 模型输出文本
     */
    suspend fun infer(
        prompt: String,
        image: Bitmap? = null,
        maxTokens: Int = 256,
        temperature: Float = 0.7f
    ): String

    /**
     * 执行推理（纯文本）
     */
    suspend fun inferText(
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.7f
    ): String

    /**
     * 检查模型是否已加载
     */
    fun isLoaded(): Boolean

    /**
     * 获取当前设备性能等级
     */
    fun getDevicePerformance(): DevicePerformance

    /**
     * 释放模型资源
     */
    fun release()
}

/**
 * 设备性能等级
 */
enum class DevicePerformance {
    HIGH,    // 旗舰机，8GB+ RAM，支持NPU
    MEDIUM,  // 中端机，6-8GB RAM
    LOW      // 低端机，<6GB RAM
}