package com.example.isip.data.model

/**
 * 用户保存的智能相册。规则与相册本身分开建模，便于日后根据新增照片重新计算成员。
 */
data class SmartAlbum(
    val id: Long = 0,
    val name: String,
    val rule: SmartAlbumRule,
    val createdBy: String = "user",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 智能相册的本地筛选条件。
 *
 * [photoIds] 可保存用户当前确认的照片；categories/tags 则作为以后增量更新时可
 * 继续使用的规则。标签不保存模型 embedding，也不会上传原图。
 */
data class SmartAlbumRule(
    val photoIds: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val matchAllTags: Boolean = false
)
