package com.example.photoagent.domain.usecase

import com.example.photoagent.data.dao.AnalysisResultDao
import com.example.photoagent.data.model.ImageAnalysisResult
import com.example.photoagent.data.model.SearchResult
import com.example.photoagent.domain.skill.SearchPhotosSkill

/**
 * 检索照片用例
 */
class SearchPhotosUseCase(
    private val analysisResultDao: AnalysisResultDao,
    private val searchPhotosSkill: SearchPhotosSkill
) {

    /**
     * 执行检索
     */
    suspend fun search(query: String): SearchResult {
        val analyses = analysisResultDao.getAll()
        val input = SearchPhotosSkill.Input(query, analyses)
        return searchPhotosSkill.execute(input)
    }

    /**
     * 快速关键词检索（不调用大模型）
     */
    suspend fun quickSearch(keyword: String): List<ImageAnalysisResult> {
        val analyses = analysisResultDao.getAll()
        return analyses.filter { analysis ->
            analysis.tags.any { it.contains(keyword, ignoreCase = true) } ||
                    analysis.ocrText.contains(keyword, ignoreCase = true) ||
                    analysis.description.contains(keyword, ignoreCase = true) ||
                    analysis.categories.any { it.contains(keyword, ignoreCase = true) }
        }
    }

    /**
     * 高级检索（结合对话历史）
     */
    suspend fun searchWithContext(query: String, conversationHistory: List<Pair<String, String>>): SearchResult {
        // 先快速检索
        val quickResults = quickSearch(query)
        if (quickResults.isEmpty()) {
            return SearchResult(query, emptyList(), 0)
        }

        // 调用大模型排序
        val input = SearchPhotosSkill.Input(
            query = query,
            analyses = quickResults
        )
        return searchPhotosSkill.execute(input)
    }

    /**
     * 获取搜索建议（基于相册内容）
     */
    suspend fun getSearchSuggestions(): List<String> {
        val analyses = analysisResultDao.getAll()
        val suggestions = mutableListOf<String>()

        // 提取高频标签作为建议
        val tagFrequency = analyses.flatMap { it.tags }
            .groupingBy { it }
            .eachCount()
            .entries
            .filter { it.value > 1 }
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }

        suggestions.addAll(tagFrequency)

        // 添加常用时间查询
        suggestions.addAll(listOf("今年", "去年", "上个月", "旅行", "聚会"))

        return suggestions.distinct().take(10)
    }
}