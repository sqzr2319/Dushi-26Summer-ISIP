package com.example.isip.data.model

/**
 * 图片分析结果数据类（简化版，移除 Room 注解）
 *
 * 这是图片内容理解的输出格式
 * 也是各模块间传递的核心数据结构
 */
data class ImageAnalysisResult(
    val photoId: String,                 // 对应 Photo.id
    val categories: List<String>,        // 分类列表，如 ["人物", "家庭", "室内"]
    val ocrText: String,                 // OCR识别出的文字
    val tags: List<String>,              // 标签列表，如 ["#旅行", "#冬天", "#哈尔滨"]
    val description: String,             // 一句话描述
    val confidence: Float,               // 综合置信度 0.0 ~ 1.0
    val analyzedAt: Long = System.currentTimeMillis()  // 分析时间
) {
    // 辅助方法：判断是否有有效内容
    fun hasContent(): Boolean = categories.isNotEmpty() || ocrText.isNotBlank() || tags.isNotEmpty()
}