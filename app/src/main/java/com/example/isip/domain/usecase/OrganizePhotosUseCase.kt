package com.example.isip.domain.usecase

import com.example.isip.data.PhotoRepository
import com.example.isip.data.model.*
import com.example.isip.utils.ImageUtils
import java.util.Calendar
import com.example.isip.domain.skill.GenerateStrategySkill

/**
 * 整理照片用例
 * 生成相册整理方案
 */
class OrganizePhotosUseCase(
    private val photoRepository: PhotoRepository,
    private val strategySkill: GenerateStrategySkill = GenerateStrategySkill()
) {

    /**
     * 生成整理方案
     * @param photoIds 指定照片ID列表（可选，不传则整理所有已分析照片）
     */
    suspend fun generateOrganizationPlan(photoIds: List<String>? = null): OrganizationPlan {
        val photos = if (photoIds != null) {
            photoIds.mapNotNull { photoRepository.getPhotoById(it) }
        } else {
            photoRepository.getAllPhotos()
        }

        val selectedIds = photos.map(Photo::id).toSet()
        val analyses = photoRepository.getAllAnalysisResults().filter { it.photoId in selectedIds }
        return strategySkill.execute(
            GenerateStrategySkill.Input(
                analyses = analyses,
                photos = photos
            )
        )
    }

    /**
     * 获取整理建议摘要
     */
    suspend fun getOrganizationSummary(): OrganizationSummary {
        val plan = generateOrganizationPlan()

        return OrganizationSummary(
            totalPhotos = plan.albums.sumOf { it.photoIds.size },
            albumCount = plan.albums.size,
            duplicateCount = plan.duplicates.sumOf { it.photoIds.size },
            privacyCount = plan.privacyRisks.size,
            suggestions = plan.suggestions
        )
    }

    /**
     * 应用整理方案（执行清理操作）
     */
    suspend fun applyOrganizationPlan(plan: OrganizationPlan, confirmations: OrganizationConfirmations): Boolean {
        // TODO: 实现整理方案的应用
        // 1. 删除重复照片（用户确认的）
        // 2. 创建相册
        // 3. 移动/加密隐私照片
        return true
    }

    /**
     * 生成事件相册（按时间和地点分组）
     */
    private suspend fun generateEventAlbums(photos: List<Photo>): List<EventAlbum> {
        val albums = mutableListOf<EventAlbum>()

        // 按年月分组
        val photosByMonth = photos.groupBy { photo ->
            val calendar = Calendar.getInstance().apply {
                timeInMillis = photo.dateTaken
            }
            "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH) + 1}"
        }

        photosByMonth.forEach { (yearMonth, monthPhotos) ->
            if (monthPhotos.size >= 5) { // 至少5张照片才创建相册
                val parts = yearMonth.split("-")
                val year = parts[0]
                val month = parts[1]

                albums.add(
                    EventAlbum(
                        id = "album_$yearMonth",
                        name = "${year}年${month}月",
                        eventDate = yearMonth,
                        coverPhotoId = monthPhotos.first().id,
                        photoIds = monthPhotos.map { it.id },
                        description = "包含 ${monthPhotos.size} 张照片"
                    )
                )
            }
        }

        // 检测旅行相册（有GPS且时间连续）
        val photosWithGps = photos.filter { it.latitude != null && it.longitude != null }
        if (photosWithGps.size >= 3) {
            albums.add(
                EventAlbum(
                    id = "album_travel",
                    name = "旅行照片",
                    eventDate = null,
                    coverPhotoId = photosWithGps.first().id,
                    photoIds = photosWithGps.map { it.id },
                    description = "包含位置信息的照片"
                )
            )
        }

        return albums
    }

    /**
     * 检测重复照片（基于文件大小和尺寸）
     */
    private fun detectDuplicates(photos: List<Photo>): List<DuplicateGroup> {
        val duplicates = mutableListOf<DuplicateGroup>()
        val processed = mutableSetOf<String>()

        photos.forEach { photo1 ->
            if (photo1.id in processed) return@forEach

            val similar = photos.filter { photo2 ->
                photo2.id != photo1.id && photo2.id !in processed &&
                ImageUtils.calculateSimilarity(photo1, photo2) > 0.95f
            }

            if (similar.isNotEmpty()) {
                val group = listOf(photo1) + similar
                processed.addAll(group.map { it.id })

                // 推荐保留文件最大的（质量通常最好）
                val recommendKeep = group.maxByOrNull { it.sizeBytes }?.id ?: photo1.id

                duplicates.add(
                    DuplicateGroup(
                        photoIds = group.map { it.id },
                        similarity = 0.95f,
                        recommendKeep = recommendKeep
                    )
                )
            }
        }

        return duplicates
    }

    /**
     * 检测隐私风险（基于分析结果）
     */
    private suspend fun detectPrivacyRisks(photos: List<Photo>): List<PrivacyAlert> {
        val privacyRisks = mutableListOf<PrivacyAlert>()

        photos.forEach { photo ->
            val analysis = photoRepository.getAnalysisResult(photo.id)
            if (analysis != null) {
                // 检测截图（可能包含隐私信息）
                if (analysis.categories.contains("截图") || photo.fileName.contains("screenshot", ignoreCase = true)) {
                    privacyRisks.add(
                        PrivacyAlert(
                            photoId = photo.id,
                            privacyType = "聊天截图",
                            description = "截图可能包含聊天记录、个人信息",
                            suggestion = "建议定期清理不需要的截图"
                        )
                    )
                }

                // 检测 OCR 文本中的敏感词
                val sensitiveKeywords = listOf("身份证", "银行卡", "密码", "账号")
                sensitiveKeywords.forEach { keyword ->
                    if (analysis.ocrText.contains(keyword, ignoreCase = true)) {
                        privacyRisks.add(
                            PrivacyAlert(
                                photoId = photo.id,
                                privacyType = keyword,
                                description = "检测到可能包含${keyword}信息",
                                suggestion = "建议加密或删除此类照片"
                            )
                        )
                    }
                }
            }
        }

        return privacyRisks
    }

    /**
     * 生成整理建议
     */
    private fun generateSuggestions(
        albums: List<EventAlbum>,
        duplicates: List<DuplicateGroup>,
        privacyRisks: List<PrivacyAlert>
    ): List<String> {
        val suggestions = mutableListOf<String>()

        if (albums.isNotEmpty()) {
            suggestions.add("建议创建 ${albums.size} 个相册来整理照片")
        }

        if (duplicates.isNotEmpty()) {
            val duplicateCount = duplicates.sumOf { it.photoIds.size - 1 }
            suggestions.add("发现 ${duplicateCount} 张重复照片可以删除，节省空间")
        }

        if (privacyRisks.isNotEmpty()) {
            suggestions.add("发现 ${privacyRisks.size} 张照片可能包含隐私信息，建议检查")
        }

        if (suggestions.isEmpty()) {
            suggestions.add("您的相册整理得很好！")
        }

        return suggestions
    }
}

/**
 * 整理摘要
 */
data class OrganizationSummary(
    val totalPhotos: Int,
    val albumCount: Int,
    val duplicateCount: Int,
    val privacyCount: Int,
    val suggestions: List<String>
)

/**
 * 用户确认项
 */
data class OrganizationConfirmations(
    val confirmedDuplicates: List<String>,       // 确认删除的重复组ID
    val confirmedPrivacy: List<String>,          // 确认处理的隐私照片ID
    val albumNames: Map<String, String>          // 相册ID → 自定义名称
)
