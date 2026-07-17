package com.example.isip.domain.usecase

import com.example.isip.data.model.CleanupCandidate
import com.example.isip.data.model.CleanupReview
import com.example.isip.data.model.OrganizationPlan
import com.example.isip.data.model.Photo
import com.example.isip.domain.skill.DeletePhotoSkill
import com.example.isip.domain.skill.ReviewCleanupSkill

/**
 * Coordinates the destructive part of an organization plan.
 *
 * Strategy generation remains read-only. This use case first creates a review,
 * then an exact one-time delete request, and deletes only after confirmation.
 */
class CleanupCoordinatorUseCase(
    private val reviewCleanupSkill: ReviewCleanupSkill,
    private val deletePhotoSkill: DeletePhotoSkill
) {

    data class ReviewInput(
        val candidates: List<CleanupCandidate>,
        val mediaSizeBytes: Map<String, Long>,
        val selectedCandidateIds: Set<String>? = null,
        val keepOverrides: Map<String, String> = emptyMap(),
        val protectedMediaIds: Set<String> = emptySet()
    )

    suspend fun review(input: ReviewInput): CleanupReview = reviewCleanupSkill.execute(
        ReviewCleanupSkill.Input(
            candidates = input.candidates,
            selectedCandidateIds = input.selectedCandidateIds,
            keepOverrides = input.keepOverrides,
            protectedMediaIds = input.protectedMediaIds,
            mediaSizeBytes = input.mediaSizeBytes
        )
    )

    suspend fun reviewPhotoDuplicates(
        plan: OrganizationPlan,
        photos: List<Photo>,
        selectedCandidateIds: Set<String>? = null,
        keepOverrides: Map<String, String> = emptyMap(),
        protectedPhotoIds: Set<String> = emptySet()
    ): CleanupReview = review(
        ReviewInput(
            candidates = ReviewCleanupSkill.fromPhotoDuplicates(plan.duplicates, photos),
            mediaSizeBytes = photos.associate { it.id to it.sizeBytes },
            selectedCandidateIds = selectedCandidateIds,
            keepOverrides = keepOverrides,
            protectedMediaIds = protectedPhotoIds
        )
    )

    fun requestDeleteAfterReview(
        review: CleanupReview,
        reason: String = "用户确认执行整理方案"
    ): DeletePhotoSkill.DeleteRequest {
        require(review.requiresUserConfirmation) { "清理复核结果必须要求用户确认" }
        require(review.deleteIds.isNotEmpty()) { "复核结果中没有可删除的照片" }
        return deletePhotoSkill.requestDeleteAfterConfirmation(review.deleteIds, reason)
    }

    suspend fun confirmDeletion(
        requestId: String,
        approved: Boolean,
        deletionCompletedBySystem: Boolean = false
    ): DeletePhotoSkill.DeleteResult = deletePhotoSkill.confirm(
        confirmation = DeletePhotoSkill.Confirmation(requestId, approved),
        deletionCompletedBySystem = deletionCompletedBySystem
    )
}
