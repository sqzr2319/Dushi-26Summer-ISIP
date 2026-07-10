package com.example.photoagent.data.model

/**
 * 检索结果数据类
 * 
 * 这是模糊检索Skill（孙长毅）的输出格式
 */
data class SearchResult(
    val query: String,                      // 用户原始查询
    val results: List<SearchItem>,          // 匹配结果列表（按相关度降序）
    val totalCount: Int,                    // 匹配总数
    val searchTime: Long = System.currentTimeMillis()
)

/**
 * 检索结果项
 */
data class SearchItem(
    val photoId: String,
    val relevanceScore: Float,              // 相关度评分 0.0 ~ 1.0
    val matchedTags: List<String>,          // 匹配到的标签
    val matchedText: String                 // 匹配到的文本（OCR或描述）
)