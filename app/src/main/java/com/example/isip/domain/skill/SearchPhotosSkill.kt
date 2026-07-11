package com.example.isip.domain.skill

import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.SearchItem
import com.example.isip.data.model.SearchResult
import kotlin.math.max

/**
 * 使用自然语言检索已分析照片的 Agent Skill。
 *
 * 这一层不直接加载 MobileCLIP/Qwen 权重，而是组合三种能力：
 * 1. 基于标签、分类、OCR 和描述的本地词法匹配；
 * 2. 可选的 QueryExpander（例如 Qwen），用于产生适合 CLIP 的英文查询与同义词；
 * 3. 可选的 ClipSearchEngine，用于文本-图像向量召回。
 *
 * 任一可选模型不可用或推理失败时，检索会自动退化为纯本地词法匹配。
 */
class SearchPhotosSkill(
    private val clipSearchEngine: ClipSearchEngine? = null,
    private val queryExpander: QueryExpander? = null
) : Skill<SearchPhotosSkill.Input, SearchResult> {

    data class Input(
        val query: String,
        val analyses: List<ImageAnalysisResult>,
        val topK: Int = DEFAULT_TOP_K,
        val minRelevance: Float = DEFAULT_MIN_RELEVANCE
    )

    /**
     * MobileCLIP 适配层的输出。
     *
     * [photoId] 必须与 [ImageAnalysisResult.photoId] 使用同一 ID。
     * [relevanceScore] 应由适配层将原始余弦相似度校准到 0..1。
     */
    data class ClipMatch(
        val photoId: String,
        val relevanceScore: Float,
        val explanation: String = ""
    )

    /** Qwen 等语言模型可输出的查询扩展结果。 */
    data class QueryExpansion(
        val semanticQueries: List<String>,
        val keywords: List<String> = emptyList()
    )

    fun interface ClipSearchEngine {
        /**
         * 对已建立图像向量索引的照片执行文本检索。
         * 
         * 实现应忽略不在 [candidatePhotoIds] 中的索引项，并最多返回 [limit] 条。
         */
        suspend fun search(
            query: String,
            candidatePhotoIds: Set<String>,
            limit: Int
        ): List<ClipMatch>
    }

    fun interface QueryExpander {
        /** 将用户原始查询改写为适合词法匹配和 CLIP 的查询。 */
        suspend fun expand(query: String): QueryExpansion
    }

    private data class LexicalMatch(
        val score: Float,
        val matchedTags: List<String>,
        val matchedText: String
    )

    override suspend fun execute(input: Input): SearchResult {
        val query = input.query.trim()
        require(query.isNotEmpty()) { "query 不能为空" }
        require(input.topK > 0) { "topK 必须大于 0" }
        require(input.minRelevance in 0f..1f) { "minRelevance 必须在 0..1 之间" }

        if (input.analyses.isEmpty()) {
            return SearchResult(query = query, results = emptyList(), totalCount = 0)
        }

        // 同一照片可能有多次分析结果，这里保留最新的一条。
        val analyses = input.analyses
            .groupBy(ImageAnalysisResult::photoId)
            .mapValues { (_, values) -> values.maxBy { it.analyzedAt } }

        val expansion = expandQuerySafely(query)
        val lexicalTerms = buildList {
            add(query)
            addAll(expansion.keywords)
            addAll(expansion.semanticQueries)
        }.filter { it.isNotBlank() }.distinct()

        val lexicalMatches = analyses.mapValues { (_, analysis) ->
            lexicalMatch(lexicalTerms, analysis)
        }

        val clipMatches = searchClipSafely(
            originalQuery = query,
            expansion = expansion,
            photoIds = analyses.keys,
            limit = max(input.topK * CANDIDATE_MULTIPLIER, MIN_SEMANTIC_CANDIDATES)
        )

        val ranked = analyses.values.mapNotNull { analysis ->
            val lexical = lexicalMatches.getValue(analysis.photoId)
            val semantic = clipMatches[analysis.photoId]
            val score = fuseScores(lexical.score, semantic?.relevanceScore)

            if (score < input.minRelevance) return@mapNotNull null

            SearchItem(
                photoId = analysis.photoId,
                relevanceScore = score,
                matchedTags = lexical.matchedTags,
                matchedText = lexical.matchedText.ifBlank {
                    semantic?.explanation?.takeIf { it.isNotBlank() }
                        ?: analysis.description.ifBlank { analysis.ocrText }
                }
            )
        }.sortedWith(
            compareByDescending<SearchItem> { it.relevanceScore }
                .thenBy { it.photoId }
        )

        return SearchResult(
            query = query,
            results = ranked.take(input.topK),
            totalCount = ranked.size
        )
    }

    private suspend fun expandQuerySafely(query: String): QueryExpansion {
        val expanded = try {
            queryExpander?.expand(query)
        } catch (_: Exception) {
            null
        }

        val semanticQueries = buildList {
            add(query)
            expanded?.semanticQueries?.let(::addAll)
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_SEMANTIC_QUERIES)

        return QueryExpansion(
            semanticQueries = semanticQueries,
            keywords = expanded?.keywords.orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        )
    }

    private suspend fun searchClipSafely(
        originalQuery: String,
        expansion: QueryExpansion,
        photoIds: Set<String>,
        limit: Int
    ): Map<String, ClipMatch> {
        val engine = clipSearchEngine ?: return emptyMap()
        val safeLimit = limit.coerceAtMost(MAX_SEMANTIC_CANDIDATES)
        val matches = mutableMapOf<String, ClipMatch>()

        // 英文预训练的 MobileCLIP 对中文检索较弱，因此优先使用扩展查询，
        // 但始终保留原始查询作为降级。
        val queries = (expansion.semanticQueries + originalQuery)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_SEMANTIC_QUERIES)

        for (semanticQuery in queries) {
            val current = try {
                engine.search(semanticQuery, photoIds, safeLimit)
            } catch (_: Exception) {
                continue
            }

            current.asSequence()
                .filter { it.photoId in photoIds }
                .forEach { match ->
                    val normalized = match.copy(
                        relevanceScore = match.relevanceScore.coerceIn(0f, 1f)
                    )
                    val previous = matches[match.photoId]
                    if (previous == null || normalized.relevanceScore > previous.relevanceScore) {
                        matches[match.photoId] = normalized
                    }
                }
        }

        return matches
    }

    private fun lexicalMatch(
        queryTerms: List<String>,
        analysis: ImageAnalysisResult
    ): LexicalMatch {
        val terms = queryTerms.flatMap(::tokenize).distinct()
        if (terms.isEmpty()) return LexicalMatch(0f, emptyList(), "")

        val tagScores = analysis.tags.associateWith { fieldScore(it, terms) }
        val categoryScores = analysis.categories.associateWith { fieldScore(it, terms) }
        val ocrScore = fieldScore(analysis.ocrText, terms)
        val descriptionScore = fieldScore(analysis.description, terms)

        val tagScore = tagScores.values.maxOrNull() ?: 0f
        val categoryScore = categoryScores.values.maxOrNull() ?: 0f
        val confidenceFactor = (0.8f + analysis.confidence.coerceIn(0f, 1f) * 0.2f)
        val score = (
            tagScore * TAG_WEIGHT +
                categoryScore * CATEGORY_WEIGHT +
                ocrScore * OCR_WEIGHT +
                descriptionScore * DESCRIPTION_WEIGHT
            ) * confidenceFactor

        val matchedTags = tagScores.filterValues { it > 0f }.keys.toList()
        val matchedText = when {
            ocrScore > 0f -> analysis.ocrText.take(MAX_MATCHED_TEXT_LENGTH)
            descriptionScore > 0f -> analysis.description.take(MAX_MATCHED_TEXT_LENGTH)
            categoryScore > 0f -> analysis.categories.joinToString(" / ")
            tagScore > 0f -> matchedTags.joinToString(" ")
            else -> ""
        }

        return LexicalMatch(
            score = score.coerceIn(0f, 1f),
            matchedTags = matchedTags,
            matchedText = matchedText
        )
    }

    private fun tokenize(text: String): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()

        val tokens = linkedSetOf(normalized)
        TOKEN_REGEX.findAll(text.lowercase()).forEach { match ->
            val token = normalize(match.value)
            if (token.length >= MIN_TOKEN_LENGTH && token !in STOP_WORDS) {
                tokens += token
                if (token.any(::isCjk) && token.length > CJK_GRAM_SIZE) {
                    token.windowed(CJK_GRAM_SIZE).filterTo(tokens) { it !in STOP_WORDS }
                }
            }
        }
        return tokens.toList()
    }

    private fun fieldScore(field: String, terms: List<String>): Float {
        val normalizedField = normalize(field)
        if (normalizedField.isEmpty()) return 0f

        var best = 0f
        for (term in terms) {
            if (term.isEmpty()) continue
            val score = when {
                normalizedField == term -> 1f
                normalizedField.contains(term) -> 0.9f
                term.length >= MIN_TOKEN_LENGTH && term.contains(normalizedField) -> 0.8f
                else -> 0f
            }
            if (score > best) best = score
        }
        return best
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace("#", "")
        .replace(NON_SEARCHABLE_REGEX, "")

    private fun isCjk(char: Char): Boolean = char.code in 0x3400..0x9FFF

    private fun fuseScores(lexicalScore: Float, semanticScore: Float?): Float = when {
        semanticScore == null -> lexicalScore
        lexicalScore <= 0f -> semanticScore
        else -> (lexicalScore * LEXICAL_FUSION_WEIGHT +
            semanticScore * SEMANTIC_FUSION_WEIGHT + BOTH_MATCH_BONUS).coerceAtMost(1f)
    }

    override fun getToolDescription(): String = """
        |## 工具名称
        |search_photos
        |
        |## 功能
        |根据用户的自然语言描述检索本地照片，结合标签/OCR/描述匹配和图文向量相似度排序。
        |
        |## 输入参数
        |- query (String, 必填): 用户的搜索描述
        |- top_k (Int, 可选): 最多返回条数，默认 20
        |照片分析结果和向量索引由 SkillRegistry 从本地数据层注入，不由模型在 params 中生成。
        |
        |## 输出格式
        |{"query":"去年冬天在哈尔滨吃饭","results":[{"photoId":"img001","relevanceScore":0.92,"matchedTags":["#旅行","#冬天"],"matchedText":"哈尔滨聚餐"}],"totalCount":1}
        |
        |## 调用示例
        |{"tool":"search_photos","params":{"query":"去年冬天在哈尔滨吃饭","top_k":20}}
    """.trimMargin()

    companion object {
        const val DEFAULT_TOP_K = 20
        const val DEFAULT_MIN_RELEVANCE = 0.1f

        private const val TAG_WEIGHT = 0.35f
        private const val CATEGORY_WEIGHT = 0.25f
        private const val OCR_WEIGHT = 0.22f
        private const val DESCRIPTION_WEIGHT = 0.18f
        private const val LEXICAL_FUSION_WEIGHT = 0.4f
        private const val SEMANTIC_FUSION_WEIGHT = 0.6f
        private const val BOTH_MATCH_BONUS = 0.05f
        private const val CANDIDATE_MULTIPLIER = 4
        private const val MIN_SEMANTIC_CANDIDATES = 50
        private const val MAX_SEMANTIC_CANDIDATES = 200
        private const val MAX_SEMANTIC_QUERIES = 4
        private const val MAX_MATCHED_TEXT_LENGTH = 160
        private const val MIN_TOKEN_LENGTH = 2
        private const val CJK_GRAM_SIZE = 2

        private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        private val NON_SEARCHABLE_REGEX = Regex("[^\\p{L}\\p{N}]")
        private val STOP_WORDS = setOf(
            "的", "了", "和", "在", "是", "有", "照片", "图片", "photo", "photos", "image", "images"
        )
    }
}
