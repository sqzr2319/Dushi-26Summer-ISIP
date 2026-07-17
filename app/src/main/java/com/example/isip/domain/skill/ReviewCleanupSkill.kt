package com.example.isip.domain.skill

import com.example.isip.data.model.CleanupCandidate
import com.example.isip.data.model.CleanupCandidateType
import com.example.isip.data.model.CleanupReview
import com.example.isip.data.model.DuplicateGroup
import com.example.isip.data.model.DuplicateVideoGroup
import com.example.isip.data.model.Photo
import com.example.isip.data.model.ReviewedCleanupItem

/** Converts cleanup recommendations into an explicit, user-reviewable delete set. */
class ReviewCleanupSkill : Skill<ReviewCleanupSkill.Input, CleanupReview> {

    data class Input(
        val candidates: List<CleanupCandidate>,
        val selectedCandidateIds: Set<String>? = null,
        val keepOverrides: Map<String, String> = emptyMap(),
        val protectedMediaIds: Set<String> = emptySet(),
        val mediaSizeBytes: Map<String, Long> = emptyMap(),
        val lowConfidenceThreshold: Float = DEFAULT_LOW_CONFIDENCE_THRESHOLD
    )

    override suspend fun execute(input: Input): CleanupReview {
        require(input.lowConfidenceThreshold in 0f..1f) {
            "lowConfidenceThreshold 必须在 0..1 之间"
        }
        val selected = input.candidates.distinctBy(CleanupCandidate::id).filter { candidate ->
            input.selectedCandidateIds == null || candidate.id in input.selectedCandidateIds
        }
        val globallyScheduled = linkedSetOf<String>()
        val reviewItems = selected.mapNotNull { candidate ->
            val ids = candidate.mediaIds.filter(String::isNotBlank).distinct()
            if (ids.isEmpty()) return@mapNotNull null
            val requiresKeep = candidate.type == CleanupCandidateType.DUPLICATE_PHOTO ||
                candidate.type == CleanupCandidateType.DUPLICATE_VIDEO
            val requestedKeep = input.keepOverrides[candidate.id] ?: candidate.recommendedKeepId
            val keep = requestedKeep?.takeIf { it in ids }
            val warnings = buildList {
                if (candidate.confidence !in input.lowConfidenceThreshold..1f) {
                    add("候选 ${candidate.id} 置信度较低，需要人工复核")
                }
                if (requiresKeep && keep == null) add("候选 ${candidate.id} 尚未选择保留项")
                if (requestedKeep != null && requestedKeep !in ids) {
                    add("候选 ${candidate.id} 的保留项不在候选集合中")
                }
            }.toMutableList()
            val deleteIds = if (requiresKeep && keep == null) emptyList() else ids.filterNot {
                it == keep || it in input.protectedMediaIds || it in globallyScheduled
            }
            val protected = ids.filter { it in input.protectedMediaIds }
            if (protected.isNotEmpty()) warnings += "已保护 ${protected.size} 个媒体文件，不会删除"
            globallyScheduled += deleteIds
            ReviewedCleanupItem(
                candidateId = candidate.id,
                type = candidate.type,
                keepId = keep,
                deleteIds = deleteIds,
                reclaimableBytes = deleteIds.sumOf { input.mediaSizeBytes[it]?.coerceAtLeast(0L) ?: 0L },
                reason = candidate.reason,
                warnings = warnings
            )
        }
        val deleteIds = reviewItems.flatMap(ReviewedCleanupItem::deleteIds).distinct()
        val warnings = reviewItems.flatMap(ReviewedCleanupItem::warnings).distinct()
        return CleanupReview(
            items = reviewItems,
            deleteIds = deleteIds,
            reclaimableBytes = deleteIds.sumOf { input.mediaSizeBytes[it]?.coerceAtLeast(0L) ?: 0L },
            warnings = warnings
        )
    }

    override fun getToolDescription(): String = """
        |## 工具名称
        |review_cleanup
        |## 功能
        |复核清理候选，保护用户指定媒体，为每组选择保留项，并计算待删除集合及预计释放空间。
        |## 安全约束
        |本工具不执行删除；输出必须继续交给用户确认。
    """.trimMargin()

    companion object {
        const val DEFAULT_LOW_CONFIDENCE_THRESHOLD = 0.9f

        fun fromPhotoDuplicates(
            groups: List<DuplicateGroup>,
            photos: List<Photo>
        ): List<CleanupCandidate> {
            val byId = photos.associateBy(Photo::id)
            return groups.mapIndexed { index, group ->
                CleanupCandidate(
                    id = "photo_duplicates_$index",
                    type = CleanupCandidateType.DUPLICATE_PHOTO,
                    mediaIds = group.photoIds,
                    recommendedKeepId = group.recommendKeep,
                    reason = "候选重复照片",
                    confidence = group.similarity,
                    totalBytes = group.photoIds.sumOf { byId[it]?.sizeBytes ?: 0L }
                )
            }
        }

        fun fromVideoDuplicates(groups: List<DuplicateVideoGroup>): List<CleanupCandidate> =
            groups.map { group ->
                CleanupCandidate(
                    id = group.id,
                    type = CleanupCandidateType.DUPLICATE_VIDEO,
                    mediaIds = group.videoIds,
                    recommendedKeepId = group.recommendKeep,
                    reason = group.reason,
                    confidence = group.similarity,
                    totalBytes = group.totalBytes
                )
            }
    }
}
