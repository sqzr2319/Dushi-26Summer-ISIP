package com.example.isip.domain.skill

import com.example.isip.data.model.DuplicateGroup
import com.example.isip.data.model.EventAlbum
import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.OrganizationPlan
import com.example.isip.data.model.Photo
import com.example.isip.data.model.PrivacyAlert

/** 根据结构化图片理解结果生成整理方案（李佳乔负责）。 */
class GenerateStrategySkill(
    private val clipSimilarityEngine: ClipSimilarityEngine? = null
) : Skill<GenerateStrategySkill.Input, OrganizationPlan> {

    data class Input(
        val analyses: List<ImageAnalysisResult>,
        val photos: List<Photo> = emptyList(),
        val duplicateThreshold: Float = DEFAULT_DUPLICATE_THRESHOLD,
        val minimumAlbumSize: Int = DEFAULT_MINIMUM_ALBUM_SIZE
    )

    data class SimilarPair(val firstPhotoId: String, val secondPhotoId: String, val similarity: Float)

    fun interface ClipSimilarityEngine {
        /** 从已缓存的 CLIP embedding 中找近邻，不应在此重新解码图片。 */
        suspend fun findSimilar(photoIds: Set<String>, threshold: Float): List<SimilarPair>
    }

    override suspend fun execute(input: Input): OrganizationPlan {
        require(input.duplicateThreshold in 0f..1f) { "duplicateThreshold 必须在 0..1 之间" }
        require(input.minimumAlbumSize > 0) { "minimumAlbumSize 必须大于 0" }

        val analyses = input.analyses.groupBy(ImageAnalysisResult::photoId)
            .mapValues { (_, rows) -> rows.maxBy { it.analyzedAt } }.values.toList()
        val photoById = input.photos.associateBy(Photo::id)
        val albums = buildAlbums(analyses, input.minimumAlbumSize)
        val duplicates = buildDuplicates(analyses.map { it.photoId }.toSet(), photoById, input.duplicateThreshold)
        val privacyRisks = analyses.flatMap(::detectPrivacy).distinctBy { it.photoId to it.privacyType }
        val suggestions = buildSuggestions(albums, duplicates, privacyRisks)
        return OrganizationPlan(albums, duplicates, privacyRisks, suggestions)
    }

    private fun buildAlbums(analyses: List<ImageAnalysisResult>, minimumSize: Int): List<EventAlbum> =
        analyses.flatMap { analysis ->
            analysis.categories.filterNot { it in GENERIC_CATEGORIES }.map { it to analysis.photoId }
        }.groupBy({ it.first }, { it.second }).mapNotNull { (category, ids) ->
            val uniqueIds = ids.distinct()
            if (uniqueIds.size < minimumSize) null else EventAlbum(
                id = "album_${category.hashCode().toUInt().toString(16)}",
                name = "$category 相册",
                eventDate = null,
                coverPhotoId = uniqueIds.first(),
                photoIds = uniqueIds,
                description = "依据图片理解结果自动归类，共 ${uniqueIds.size} 张"
            )
        }.sortedByDescending { it.photoIds.size }

    private suspend fun buildDuplicates(
        ids: Set<String>, photos: Map<String, Photo>, threshold: Float
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
        albums: List<EventAlbum>, duplicates: List<DuplicateGroup>, risks: List<PrivacyAlert>
    ): List<String> = buildList {
        if (albums.isNotEmpty()) add("可创建 ${albums.size} 个智能相册")
        if (duplicates.isNotEmpty()) add("CLIP 相似度发现 ${duplicates.sumOf { it.photoIds.size - 1 }} 张候选重复照片，请确认后清理")
        if (risks.isNotEmpty()) add("发现 ${risks.size} 项隐私风险，请在本机检查")
        if (isEmpty()) add("暂未发现需要整理的项目")
    }

    override fun getToolDescription(): String = """
        |## 工具名称
        |generate_strategy
        |## 功能
        |根据图片分析结果生成智能相册、CLIP 相似照片候选、隐私提醒和整理建议；所有删除操作必须由用户确认。
        |## 输入参数
        |- photo_ids (Array<String>, 可选): 待整理照片范围；分析结果由本地数据层注入。
        |## 输出
        |OrganizationPlan：albums、duplicates、privacyRisks、suggestions。
    """.trimMargin()

    companion object {
        const val DEFAULT_DUPLICATE_THRESHOLD = 0.94f
        const val DEFAULT_MINIMUM_ALBUM_SIZE = 2
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
