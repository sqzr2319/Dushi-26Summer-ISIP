package com.example.isip.domain.skill

import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.Photo

/** Creates a deterministic local summary of the current photo selection. */
class SummarizeSelectionSkill :
    Skill<SummarizeSelectionSkill.Input, SummarizeSelectionSkill.SelectionSummary> {

    data class Input(
        val photos: List<Photo>,
        val analyses: List<ImageAnalysisResult> = emptyList(),
        val maxTerms: Int = DEFAULT_MAX_TERMS
    )

    data class SelectionSummary(
        val photoIds: List<String>,
        val count: Int,
        val totalBytes: Long,
        val earliestTakenAt: Long?,
        val latestTakenAt: Long?,
        val categoryCounts: Map<String, Int>,
        val topTags: List<String>,
        val analyzedCount: Int,
        val unanalyzedCount: Int,
        val privacyRiskCount: Int,
        val landscapeCount: Int,
        val portraitCount: Int,
        val squareCount: Int,
        val text: String
    )

    override suspend fun execute(input: Input): SelectionSummary {
        require(input.maxTerms > 0) { "maxTerms 必须大于 0" }
        val photos = input.photos.distinctBy(Photo::id)
        val ids = photos.map(Photo::id)
        val latestAnalysis = input.analyses.filter { it.photoId in ids }
            .groupBy(ImageAnalysisResult::photoId)
            .mapValues { (_, rows) -> rows.maxBy(ImageAnalysisResult::analyzedAt) }
        val categories = latestAnalysis.values.flatMap(ImageAnalysisResult::categories)
            .map(String::trim).filter(String::isNotEmpty)
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .associate { it.key to it.value }
        val tags = latestAnalysis.values.flatMap(ImageAnalysisResult::tags)
            .map { it.trim().removePrefix("#") }.filter(String::isNotEmpty)
            .groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(input.maxTerms).map { "#${it.key}" }
        val riskCount = latestAnalysis.values.count { analysis ->
            val text = (analysis.categories + analysis.tags + analysis.ocrText).joinToString(" ")
            PRIVACY_PATTERN.containsMatchIn(text)
        }
        val analyzedCount = ids.count { it in latestAnalysis }
        val totalBytes = photos.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        val earliest = photos.minOfOrNull(Photo::dateTaken)
        val latest = photos.maxOfOrNull(Photo::dateTaken)
        val landscape = photos.count { it.width > it.height }
        val portrait = photos.count { it.height > it.width }
        val square = photos.size - landscape - portrait
        val categoryText = categories.entries.take(input.maxTerms)
            .joinToString("、") { "${it.key} ${it.value} 张" }
            .ifBlank { "暂无分类" }
        return SelectionSummary(
            photoIds = ids,
            count = photos.size,
            totalBytes = totalBytes,
            earliestTakenAt = earliest,
            latestTakenAt = latest,
            categoryCounts = categories,
            topTags = tags,
            analyzedCount = analyzedCount,
            unanalyzedCount = photos.size - analyzedCount,
            privacyRiskCount = riskCount,
            landscapeCount = landscape,
            portraitCount = portrait,
            squareCount = square,
            text = "已选择 ${photos.size} 张照片，共 ${formatBytes(totalBytes)}；$categoryText；" +
                "已分析 $analyzedCount 张，未分析 ${photos.size - analyzedCount} 张。"
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    override fun getToolDescription(): String = """
        |## 工具名称
        |summarize_selection
        |## 功能
        |在本地汇总选中照片的数量、空间、时间范围、分类、标签、分析覆盖率和隐私提醒，不调用大模型。
    """.trimMargin()

    companion object {
        const val DEFAULT_MAX_TERMS = 5
        private val PRIVACY_PATTERN = Regex(
            "身份证|银行卡|信用卡|密码|验证码|手机号|聊天记录|\\b\\d{16,19}\\b",
            RegexOption.IGNORE_CASE
        )
    }
}
