package com.example.isip.domain.skill

import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.SearchItem
import com.example.isip.data.model.SearchResult

/**
 * 本地语义检索。
 *
 * 查询阶段只计算 MobileCLIP 文本 embedding，并融合已持久化的标签、分类、描述和
 * OCR；不会再次传输或分析图片。
 */
class SemanticSearchSkill(
    private val clipSearchEngine: SearchPhotosSkill.ClipSearchEngine? = null
) : Skill<SemanticSearchSkill.Input, SearchResult> {

    data class Input(
        val query: String,
        val analyses: List<ImageAnalysisResult>,
        /** All media eligible for CLIP search, including items without OCR/tag rows. */
        val candidatePhotoIds: Set<String> = analyses.mapTo(linkedSetOf()) { it.photoId },
        val topK: Int = DEFAULT_TOP_K,
        val minRelevance: Float = DEFAULT_MIN_RELEVANCE
    )

    private data class LocalMatch(
        val tagScore: Float,
        val textScore: Float,
        val matchedTags: List<String>,
        val matchedText: String
    )

    override suspend fun execute(input: Input): SearchResult {
        val query = input.query.trim()
        require(query.isNotEmpty()) { "query 不能为空" }
        require(input.topK > 0) { "topK 必须大于 0" }
        require(input.minRelevance in 0f..1f) { "minRelevance 必须在 0..1 之间" }
        val analyses = input.analyses.groupBy(ImageAnalysisResult::photoId)
            .mapValues { (_, values) -> values.maxBy(ImageAnalysisResult::analyzedAt) }
        // CLIP vectors can be ready before optional OCR/tag analysis.  Therefore the
        // semantic candidate set is the full media library, not only `analyses`.
        val candidatePhotoIds = if (clipSearchEngine != null) {
            input.candidatePhotoIds.ifEmpty { analyses.keys }
        } else {
            analyses.keys
        }
        if (candidatePhotoIds.isEmpty()) return SearchResult(query, emptyList(), 0)

        val terms = tokenize(query)
        val localMatches = analyses.mapValues { (_, analysis) -> localMatch(analysis, terms) }
        val clipMatches = runCatching {
            clipSearchEngine?.search(
                query = query,
                candidatePhotoIds = candidatePhotoIds,
                limit = (input.topK * CANDIDATE_MULTIPLIER).coerceAtMost(MAX_CLIP_CANDIDATES)
            ).orEmpty()
        }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.photoId in candidatePhotoIds }
            .groupBy(SearchPhotosSkill.ClipMatch::photoId)
            .mapValues { (_, matches) -> matches.maxBy(SearchPhotosSkill.ClipMatch::relevanceScore) }

        val ranked = candidatePhotoIds.mapNotNull { photoId ->
            val analysis = analyses[photoId]
            val local = analysis?.let { localMatches.getValue(photoId) } ?: EMPTY_LOCAL_MATCH
            val clip = clipMatches[photoId]
            val score = fuse(clip?.relevanceScore, local.tagScore, local.textScore)
            if (score < input.minRelevance) return@mapNotNull null

            SearchItem(
                photoId = photoId,
                relevanceScore = score,
                matchedTags = local.matchedTags,
                matchedText = local.matchedText.ifBlank {
                    clip?.explanation?.takeIf(String::isNotBlank)
                        ?: analysis?.description?.ifBlank { analysis.ocrText }.orEmpty()
                }
            )
        }.sortedWith(compareByDescending<SearchItem>(SearchItem::relevanceScore).thenBy(SearchItem::photoId))

        return SearchResult(query = query, results = ranked.take(input.topK), totalCount = ranked.size)
    }

    private fun localMatch(analysis: ImageAnalysisResult, terms: List<String>): LocalMatch {
        val tagFields = analysis.tags + analysis.categories
        val tagScores = tagFields.associateWith { fieldScore(it, terms) }
        val ocrScore = fieldScore(analysis.ocrText, terms)
        val descriptionScore = fieldScore(analysis.description, terms)
        val textScore = maxOf(ocrScore, descriptionScore)
        val tagScore = tagScores.values.maxOrNull() ?: 0f
        val matchedTags = tagScores.filterValues { it > 0f }.keys.toList()
        val matchedText = when {
            ocrScore > 0f -> analysis.ocrText.take(MAX_MATCHED_TEXT_LENGTH)
            descriptionScore > 0f -> analysis.description.take(MAX_MATCHED_TEXT_LENGTH)
            matchedTags.isNotEmpty() -> matchedTags.joinToString(" ")
            else -> ""
        }
        return LocalMatch(tagScore, textScore, matchedTags, matchedText)
    }

    private fun fuse(semantic: Float?, tagScore: Float, textScore: Float): Float = when (semantic) {
        null -> (tagScore * LOCAL_TAG_WEIGHT + textScore * LOCAL_TEXT_WEIGHT)
        else -> (
            semantic.coerceIn(0f, 1f) * CLIP_WEIGHT +
                tagScore * TAG_WEIGHT + textScore * TEXT_WEIGHT
            ).coerceIn(0f, 1f)
    }

    private fun fieldScore(field: String, terms: List<String>): Float {
        val normalizedField = normalize(field)
        if (normalizedField.isEmpty() || terms.isEmpty()) return 0f
        val matched = terms.count { term ->
            term.length >= MIN_TERM_LENGTH &&
                (normalizedField.contains(term) || (term.length >= 3 && term.contains(normalizedField)))
        }
        return (matched.toFloat() / terms.size).coerceIn(0f, 1f)
    }

    private fun tokenize(query: String): List<String> {
        val terms = linkedSetOf<String>()
        TOKEN_REGEX.findAll(query.lowercase()).forEach { match ->
            val token = normalize(match.value)
            if (token.length >= MIN_TERM_LENGTH && token !in STOP_WORDS) {
                terms += token
                if (token.all(::isCjk) && token.length > CJK_NGRAM_SIZE) {
                    token.windowed(CJK_NGRAM_SIZE).filterTo(terms) { it !in STOP_WORDS }
                }
            }
        }
        return terms.take(MAX_TERMS)
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace("#", "")
        .replace(NON_SEARCHABLE, "")

    private fun isCjk(char: Char): Boolean = char.code in 0x3400..0x9FFF

    override fun getToolDescription(): String = """
        |## 工具名称
        |semantic_search
        |
        |## 功能
        |使用 MobileCLIP 文本-图像相似度与本地标签/分类/OCR/描述融合检索照片；搜索阶段不会再次分析图片。
        |
        |## 排序
        |有 CLIP 向量时：CLIP 70% + 标签/分类 25% + OCR/描述 5%；无 CLIP 时自动降级到本地文本匹配。
        |
        |## 输入参数
        |- query (String, 必填): 搜索描述
        |- top_k (Int, 可选): 最多返回数量，默认 20
        |
        |## 输出
        |SearchResult：query、results、totalCount。
    """.trimMargin()

    private companion object {
        val EMPTY_LOCAL_MATCH = LocalMatch(0f, 0f, emptyList(), "")
        const val DEFAULT_TOP_K = 20
        const val DEFAULT_MIN_RELEVANCE = 0.1f
        const val CLIP_WEIGHT = 0.70f
        const val TAG_WEIGHT = 0.25f
        const val TEXT_WEIGHT = 0.05f
        const val LOCAL_TAG_WEIGHT = 0.80f
        const val LOCAL_TEXT_WEIGHT = 0.20f
        const val CANDIDATE_MULTIPLIER = 4
        const val MAX_CLIP_CANDIDATES = 200
        const val MAX_MATCHED_TEXT_LENGTH = 160
        const val MIN_TERM_LENGTH = 2
        const val CJK_NGRAM_SIZE = 2
        const val MAX_TERMS = 16
        val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        val NON_SEARCHABLE = Regex("[^\\p{L}\\p{N}]")
        val STOP_WORDS = setOf("的", "了", "和", "在", "是", "有", "照片", "图片", "photo", "image")
    }
}
