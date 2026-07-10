package com.example.isip.domain.usecase

import com.example.isip.data.PhotoRepository
import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.Photo
import com.example.isip.data.model.SearchItem
import com.example.isip.data.model.SearchResult

/**
 * 检索照片用例
 */
class SearchPhotosUseCase(
    private val photoRepository: PhotoRepository
) {

    /**
     * 执行检索
     */
    suspend fun search(query: String): SearchResult {
        val allPhotos = photoRepository.getAllPhotos()
        val results = mutableListOf<SearchItem>()

        allPhotos.forEach { photo ->
            val analysisResult = photoRepository.getAnalysisResult(photo.id)
            if (analysisResult != null) {
                val score = calculateRelevance(query, photo, analysisResult)
                if (score > 0.1f) {
                    results.add(
                        SearchItem(
                            photoId = photo.id,
                            relevanceScore = score,
                            matchedTags = analysisResult.tags.filter {
                                it.contains(query, ignoreCase = true)
                            },
                            matchedText = if (analysisResult.ocrText.contains(query, ignoreCase = true)) {
                                analysisResult.ocrText.take(100)
                            } else {
                                analysisResult.description
                            }
                        )
                    )
                }
            }
        }

        // 按相关度排序
        val sortedResults = results.sortedByDescending { it.relevanceScore }

        return SearchResult(
            query = query,
            results = sortedResults,
            totalCount = sortedResults.size
        )
    }

    /**
     * 快速关键词检索（不调用大模型）
     */
    suspend fun quickSearch(keyword: String): List<ImageAnalysisResult> {
        val allPhotos = photoRepository.getAllPhotos()
        val results = mutableListOf<ImageAnalysisResult>()

        allPhotos.forEach { photo ->
            val analysis = photoRepository.getAnalysisResult(photo.id)
            if (analysis != null) {
                val matches = analysis.tags.any { it.contains(keyword, ignoreCase = true) } ||
                        analysis.ocrText.contains(keyword, ignoreCase = true) ||
                        analysis.description.contains(keyword, ignoreCase = true) ||
                        analysis.categories.any { it.contains(keyword, ignoreCase = true) }

                if (matches) {
                    results.add(analysis)
                }
            }
        }

        return results
    }

    /**
     * 获取搜索建议（基于相册内容）
     */
    suspend fun getSearchSuggestions(): List<String> {
        val allPhotos = photoRepository.getAllPhotos()
        val suggestions = mutableListOf<String>()

        // 收集所有标签
        val allTags = mutableListOf<String>()
        allPhotos.forEach { photo ->
            val analysis = photoRepository.getAnalysisResult(photo.id)
            if (analysis != null) {
                allTags.addAll(analysis.tags)
            }
        }

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

    /**
     * 计算查询与照片的相关度
     */
    private fun calculateRelevance(query: String, photo: Photo, analysis: ImageAnalysisResult): Float {
        var score = 0f

        // 标签匹配（权重最高）
        analysis.tags.forEach { tag ->
            if (tag.contains(query, ignoreCase = true)) {
                score += 0.5f
            }
        }

        // 分类匹配
        analysis.categories.forEach { category ->
            if (category.contains(query, ignoreCase = true)) {
                score += 0.3f
            }
        }

        // OCR 文本匹配
        if (analysis.ocrText.contains(query, ignoreCase = true)) {
            score += 0.4f
        }

        // 描述匹配
        if (analysis.description.contains(query, ignoreCase = true)) {
            score += 0.2f
        }

        // 文件名匹配
        if (photo.fileName.contains(query, ignoreCase = true)) {
            score += 0.1f
        }

        return score.coerceIn(0f, 1f)
    }
}
