package com.example.isip.domain.skill

import com.example.isip.data.model.DuplicateGroup
import com.example.isip.data.model.EventAlbum
import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.OrganizationPlan
import com.example.isip.data.model.Photo
import com.example.isip.data.model.PrivacyAlert
import com.example.isip.data.model.SimilarPhotoGroup
import com.example.isip.data.model.TagSuggestion

/**
 * 根据结构化图片理解结果生成只读整理方案（李佳乔负责）。
 *
 * 其他同学负责的 Skill 通过五个 Port 注入。端口未接入或执行失败时使用本地规则降级，
 * 因此后续合并不需要修改本 Skill 的主流程。生成策略阶段不创建相册、不写标签、不删除照片。
 */
class GenerateStrategySkill(
    private val semanticSearchSkillPort: SemanticSearchSkillPort? = null,
    private val createSmartAlbumSkillPort: CreateSmartAlbumSkillPort? = null,
    private val addTagSkillPort: AddTagSkillPort? = null,
    private val findDuplicatesSkillPort: FindDuplicatesSkillPort? = null,
    private val findSimilarPhotosSkillPort: FindSimilarPhotosSkillPort? = null,
    private val clipSimilarityEngine: ClipSimilarityEngine? = null
) : Skill<GenerateStrategySkill.Input, OrganizationPlan> {

    data class Input(
        val analyses: List<ImageAnalysisResult>,
        val photos: List<Photo> = emptyList(),
        val duplicateThreshold: Float = DEFAULT_DUPLICATE_THRESHOLD,
        val minimumAlbumSize: Int = DEFAULT_MINIMUM_ALBUM_SIZE
    )

    /** 提供给协作 Skill 的稳定、只读输入。 */
    data class StrategyContext(
        val analyses: List<ImageAnalysisResult>,
        val photos: List<Photo>,
        val duplicateThreshold: Float,
        val minimumAlbumSize: Int
    )

    data class SemanticGroup(
        val id: String,
        val name: String,
        val photoIds: List<String>,
        val eventDate: String? = null,
        val description: String? = null
    )

    data class AlbumPlanningInput(
        val context: StrategyContext,
        val semanticGroups: List<SemanticGroup>
    )

    data class SimilarPair(val firstPhotoId: String, val secondPhotoId: String, val similarity: Float)

    fun interface SemanticSearchSkillPort {
        suspend fun groupPhotos(context: StrategyContext): List<SemanticGroup>
    }

    fun interface CreateSmartAlbumSkillPort {
        /** 只生成相册候选；真实创建必须在用户确认后的执行阶段完成。 */
        suspend fun previewSmartAlbums(input: AlbumPlanningInput): List<EventAlbum>
    }

    fun interface AddTagSkillPort {
        /** 只返回标签建议；写回照片由 AddTagSkill 自己在确认后执行。 */
        suspend fun suggestTags(context: StrategyContext): List<TagSuggestion>
    }

    fun interface FindDuplicatesSkillPort {
        suspend fun findDuplicates(context: StrategyContext): List<DuplicateGroup>
    }

    fun interface FindSimilarPhotosSkillPort {
        suspend fun findSimilarPhotos(context: StrategyContext): List<SimilarPhotoGroup>
    }

    fun interface ClipSimilarityEngine {
        /** 旧版兼容端口；FindDuplicatesSkill 未接入时从缓存 embedding 中寻找候选。 */
        suspend fun findSimilar(photoIds: Set<String>, threshold: Float): List<SimilarPair>
    }

    override suspend fun execute(input: Input): OrganizationPlan {
        require(input.duplicateThreshold in 0f..1f) { "duplicateThreshold 必须在 0..1 之间" }
        require(input.minimumAlbumSize > 0) { "minimumAlbumSize 必须大于 0" }

        val photos = input.photos.distinctBy(Photo::id)
        val photoById = photos.associateBy(Photo::id)
        val knownIds = photos.map(Photo::id).toSet()
        val latestAnalyses = input.analyses
            .filter { knownIds.isEmpty() || it.photoId in knownIds }
            .groupBy(ImageAnalysisResult::photoId)
            .mapValues { (_, rows) -> rows.maxBy(ImageAnalysisResult::analyzedAt) }
            .values.toList()
        val baseContext = StrategyContext(
            analyses = latestAnalyses,
            photos = photos,
            duplicateThreshold = input.duplicateThreshold,
            minimumAlbumSize = input.minimumAlbumSize
        )

        val tagSuggestions = loadTagSuggestions(baseContext, knownIds)
        val tagsByPhoto = tagSuggestions.associate { it.photoId to it.tags }
        val enrichedAnalyses = latestAnalyses.map { analysis ->
            analysis.copy(tags = (analysis.tags + tagsByPhoto[analysis.photoId].orEmpty()).distinct())
        }
        val context = baseContext.copy(analyses = enrichedAnalyses)
        val semanticGroups = loadSemanticGroups(context, knownIds)
        val albums = loadAlbumCandidates(context, semanticGroups, knownIds)
        val duplicates = loadDuplicates(context, photoById, knownIds)
        val similarPhotos = loadSimilarPhotos(context, knownIds)
        val privacyRisks = enrichedAnalyses.flatMap(::detectPrivacy)
            .distinctBy { it.photoId to it.privacyType }
        val suggestions = buildSuggestions(
            albums,
            duplicates,
            similarPhotos,
            tagSuggestions,
            privacyRisks
        )
        return OrganizationPlan(
            albums = albums,
            duplicates = duplicates,
            privacyRisks = privacyRisks,
            suggestions = suggestions,
            similarPhotos = similarPhotos,
            tagSuggestions = tagSuggestions
        )
    }

    private suspend fun loadTagSuggestions(
        context: StrategyContext,
        knownIds: Set<String>
    ): List<TagSuggestion> = runCatching {
        addTagSkillPort?.suggestTags(context).orEmpty()
    }.getOrDefault(emptyList()).mapNotNull { suggestion ->
        if (suggestion.photoId !in knownIds) return@mapNotNull null
        val tags = suggestion.tags.map { it.trim().removePrefix("#") }
            .filter(String::isNotEmpty).distinct().take(MAX_TAGS_PER_PHOTO).map { "#$it" }
        suggestion.copy(tags = tags).takeIf { tags.isNotEmpty() }
    }.distinctBy(TagSuggestion::photoId)

    private suspend fun loadSemanticGroups(
        context: StrategyContext,
        knownIds: Set<String>
    ): List<SemanticGroup> {
        val external = runCatching { semanticSearchSkillPort?.groupPhotos(context) }.getOrNull()
        val groups = external ?: buildCategoryGroups(context.analyses)
        return groups.mapNotNull { group ->
            val ids = group.photoIds.filter { it in knownIds }.distinct()
            group.copy(photoIds = ids).takeIf { ids.size >= context.minimumAlbumSize }
        }.distinctBy(SemanticGroup::id)
    }

    private suspend fun loadAlbumCandidates(
        context: StrategyContext,
        groups: List<SemanticGroup>,
        knownIds: Set<String>
    ): List<EventAlbum> {
        val external = runCatching {
            createSmartAlbumSkillPort?.previewSmartAlbums(AlbumPlanningInput(context, groups))
        }.getOrNull()
        val albums = external ?: groups.map(::groupToAlbum)
        return albums.mapNotNull { album ->
            val ids = album.photoIds.filter { it in knownIds }.distinct()
            if (ids.size < context.minimumAlbumSize) null else album.copy(
                photoIds = ids,
                coverPhotoId = album.coverPhotoId.takeIf { it in ids } ?: ids.first()
            )
        }.distinctBy(EventAlbum::id).sortedByDescending { it.photoIds.size }
    }

    private suspend fun loadDuplicates(
        context: StrategyContext,
        photos: Map<String, Photo>,
        knownIds: Set<String>
    ): List<DuplicateGroup> {
        val external = runCatching { findDuplicatesSkillPort?.findDuplicates(context) }.getOrNull()
        val candidates = external ?: buildClipDuplicates(knownIds, photos, context.duplicateThreshold)
        return candidates.mapNotNull { group ->
            val ids = group.photoIds.filter { it in knownIds }.distinct()
            if (ids.size < 2 || group.similarity !in context.duplicateThreshold..1f) null else group.copy(
                photoIds = ids.sorted(),
                similarity = group.similarity.coerceIn(0f, 1f),
                recommendKeep = group.recommendKeep.takeIf { it in ids }
                    ?: ids.maxBy { photos[it]?.sizeBytes ?: 0L }
            )
        }.distinctBy { it.photoIds.toSet() }
    }

    private suspend fun loadSimilarPhotos(
        context: StrategyContext,
        knownIds: Set<String>
    ): List<SimilarPhotoGroup> = runCatching {
        findSimilarPhotosSkillPort?.findSimilarPhotos(context).orEmpty()
    }.getOrDefault(emptyList()).mapNotNull { group ->
        val ids = group.photoIds.filter { it in knownIds }.distinct()
        if (ids.size < 2 || group.similarity !in 0f..1f) null else group.copy(
            photoIds = ids,
            similarity = group.similarity.coerceIn(0f, 1f)
        )
    }.distinctBy { it.photoIds.toSet() }

    private fun buildCategoryGroups(analyses: List<ImageAnalysisResult>): List<SemanticGroup> =
        analyses.flatMap { analysis ->
            analysis.categories.filterNot { it in GENERIC_CATEGORIES }.map { it to analysis.photoId }
        }.groupBy({ it.first }, { it.second }).map { (category, ids) ->
            SemanticGroup(
                id = "semantic_${category.hashCode().toUInt().toString(16)}",
                name = category,
                photoIds = ids.distinct(),
                description = "依据图片理解结果自动归类"
            )
        }

    private fun groupToAlbum(group: SemanticGroup): EventAlbum = EventAlbum(
        id = "album_${group.id}",
        name = "${group.name} 相册",
        eventDate = group.eventDate,
        coverPhotoId = group.photoIds.first(),
        photoIds = group.photoIds,
        description = group.description?.let { "$it，共 ${group.photoIds.size} 张" }
    )

    private suspend fun buildClipDuplicates(
        ids: Set<String>,
        photos: Map<String, Photo>,
        threshold: Float
    ): List<DuplicateGroup> = FindDuplicatesSkill(
        FindDuplicatesSkill.SimilarityEngine { photoIds, requestedThreshold ->
            clipSimilarityEngine?.findSimilar(photoIds, requestedThreshold).orEmpty().map { pair ->
                FindDuplicatesSkill.SimilarPair(
                    firstPhotoId = pair.firstPhotoId,
                    secondPhotoId = pair.secondPhotoId,
                    similarity = pair.similarity
                )
            }
        }
    ).execute(
        FindDuplicatesSkill.Input(
            photos = ids.mapNotNull(photos::get),
            threshold = threshold
        )
    )

    private fun detectPrivacy(result: ImageAnalysisResult): List<PrivacyAlert> {
        val text = (result.categories + result.tags + result.ocrText).joinToString(" ")
        return PRIVACY_PATTERNS.mapNotNull { (type, pattern) ->
            if (!pattern.containsMatchIn(text)) null else PrivacyAlert(
                photoId = result.photoId,
                privacyType = type,
                description = "图片分析结果中检测到可能的${type}信息",
                suggestion = "请在本机确认，未经授权不要上传或自动删除"
            )
        }
    }

    private fun buildSuggestions(
        albums: List<EventAlbum>,
        duplicates: List<DuplicateGroup>,
        similarPhotos: List<SimilarPhotoGroup>,
        tagSuggestions: List<TagSuggestion>,
        risks: List<PrivacyAlert>
    ): List<String> = buildList {
        if (albums.isNotEmpty()) add("可创建 ${albums.size} 个智能相册")
        if (duplicates.isNotEmpty()) add("发现 ${duplicates.sumOf { it.photoIds.size - 1 }} 张候选重复照片，请确认后清理")
        if (similarPhotos.isNotEmpty()) add("发现 ${similarPhotos.size} 组相似照片，可按组浏览")
        if (tagSuggestions.isNotEmpty()) add("可为 ${tagSuggestions.size} 张照片补充标签")
        if (risks.isNotEmpty()) add("发现 ${risks.size} 项隐私风险，请在本机检查")
        if (isEmpty()) add("暂未发现需要整理的项目")
    }

    override fun getToolDescription(): String = """
        |## 工具名称
        |generate_strategy
        |## 功能
        |根据图片分析结果生成只读整理方案，并为 SemanticSearch、CreateSmartAlbum、AddTag、FindDuplicates、FindSimilarPhotos 保留协作端口。
        |## 安全约束
        |本工具不创建相册、不写标签、不删除照片；实际操作必须进入复核和用户确认流程。
        |## 输出
        |OrganizationPlan：albums、duplicates、similarPhotos、tagSuggestions、privacyRisks、suggestions。
    """.trimMargin()

    companion object {
        const val DEFAULT_DUPLICATE_THRESHOLD = 0.94f
        const val DEFAULT_MINIMUM_ALBUM_SIZE = 2
        private const val MAX_TAGS_PER_PHOTO = 8
        private val GENERIC_CATEGORIES = setOf("照片", "其他", "横向照片", "竖向照片")
        private val PRIVACY_PATTERNS = linkedMapOf(
            "身份证" to Regex("身份证|公民身份号码|\\b\\d{17}[0-9Xx]\\b"),
            "银行卡" to Regex("银行卡|信用卡|\\b\\d{16,19}\\b"),
            "密码" to Regex("密码|验证码|password|otp", RegexOption.IGNORE_CASE),
            "手机号" to Regex("手机号|电话|(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
            "聊天截图" to Regex("聊天截图|聊天记录")
        )
    }
}
