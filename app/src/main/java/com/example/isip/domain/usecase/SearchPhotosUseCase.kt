package com.example.isip.domain.usecase

import com.example.isip.data.PhotoRepository
import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.SearchResult
import com.example.isip.domain.skill.SearchPhotosSkill

/**
 * 检索照片用例
 */
class SearchPhotosUseCase(
    private val photoRepository: PhotoRepository,
    private val searchSkill: SearchPhotosSkill = SearchPhotosSkill()
) {

    /**
     * 执行检索
     */
    suspend fun search(query: String): SearchResult {
        val analyses = photoRepository.getAllAnalysisResults()

        return searchSkill.execute(
            SearchPhotosSkill.Input(
                query = query,
                analyses = analyses
            )
        )
    }

    /**
     * 快速关键词检索（不调用大模型）
     */
    suspend fun quickSearch(keyword: String): List<ImageAnalysisResult> {
        return photoRepository.getAllAnalysisResults().filter { analysis ->
            analysis.tags.any { it.contains(keyword, ignoreCase = true) } ||
                analysis.ocrText.contains(keyword, ignoreCase = true) ||
                analysis.description.contains(keyword, ignoreCase = true) ||
                analysis.categories.any { it.contains(keyword, ignoreCase = true) }
        }
    }

    /**
     * 获取搜索建议（基于相册内容）
     */
    suspend fun getSearchSuggestions(): List<String> {
        val analyses = photoRepository.getAllAnalysisResults()
        val suggestions = mutableListOf<String>()

        // 收集所有标签
        val allTags = analyses.flatMap { it.tags }

        // 提取高频标签作为建议
        val tagFrequency = allTags
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
