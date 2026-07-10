package com.example.photoagent.data.model

/**
 * 整理方案数据类
 * 
 * 这是整理策略生成Skill（李佳乔）的输出格式
 */
data class OrganizationPlan(
    val albums: List<EventAlbum>,           // 事件相册列表
    val duplicates: List<DuplicateGroup>,   // 重复照片组列表
    val privacyRisks: List<PrivacyAlert>,   // 隐私风险列表
    val suggestions: List<String>,          // 整理建议列表
    val generatedAt: Long = System.currentTimeMillis()
)

/**
 * 事件相册
 */
data class EventAlbum(
    val id: String,                         // 相册唯一标识
    val name: String,                       // 相册名称，如 "哈尔滨冬日旅行"
    val eventDate: String?,                 // 事件日期，如 "2025-12"
    val coverPhotoId: String,               // 封面照片ID
    val photoIds: List<String>,             // 该相册包含的照片ID列表
    val description: String?                // 相册描述
)

/**
 * 重复照片组
 */
data class DuplicateGroup(
    val photoIds: List<String>,             // 重复照片ID列表
    val similarity: Float,                  // 相似度 0.0 ~ 1.0
    val recommendKeep: String               // 推荐保留的照片ID（质量最佳）
)

/**
 * 隐私风险提醒
 */
data class PrivacyAlert(
    val photoId: String,
    val privacyType: String,                // 隐私类型，如 "身份证"、"银行卡"、"聊天截图"
    val description: String,                // 具体描述
    val suggestion: String                  // 建议操作，如 "建议加密"、"建议删除"
)