package com.example.isip.domain.usecase

import com.example.isip.data.PhotoRepository
import com.example.isip.domain.skill.FindSimilarPhotosSkill

/** 从详情页或 Agent 指令调用“找相似照片” Skill 的入口。 */
class FindSimilarPhotosUseCase(
    private val photoRepository: PhotoRepository,
    private val skill: FindSimilarPhotosSkill = FindSimilarPhotosSkill()
) {
    suspend fun findSimilar(
        targetPhotoId: String,
        topK: Int = FindSimilarPhotosSkill.DEFAULT_TOP_K,
        minSimilarity: Float = FindSimilarPhotosSkill.DEFAULT_MIN_SIMILARITY
    ): FindSimilarPhotosSkill.Output = skill.execute(
        FindSimilarPhotosSkill.Input(
            targetPhotoId = targetPhotoId,
            photos = photoRepository.getAllPhotos(),
            topK = topK,
            minSimilarity = minSimilarity
        )
    )
}
