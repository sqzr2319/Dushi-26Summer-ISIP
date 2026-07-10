package com.example.photoagent.ai

/**
 * 模型配置类
 */
data class ModelConfig(
    val modelPath: String,                  // 模型文件路径
    val modelType: ModelType = ModelType.QWEN_4B,
    val quantization: QuantizationType = QuantizationType.INT4,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val useGPU: Boolean = true,
    val useNPU: Boolean = true,
    val cpuThreads: Int = 4,
    val batchSize: Int = 1
)

enum class ModelType {
    QWEN_4B,
    MINICPM_V_4_6,
    CUSTOM
}

enum class QuantizationType {
    FP32,
    FP16,
    INT8,
    INT4
}