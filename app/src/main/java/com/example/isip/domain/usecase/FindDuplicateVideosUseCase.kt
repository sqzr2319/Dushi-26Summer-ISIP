package com.example.isip.domain.usecase

import com.example.isip.data.VideoRepository
import com.example.isip.data.model.DuplicateVideoGroup
import com.example.isip.domain.skill.FindDuplicateVideosSkill

/** Presentation-ready entry point for scanning and reviewing duplicate videos. */
class FindDuplicateVideosUseCase(
    private val videoRepository: VideoRepository,
    private val skill: FindDuplicateVideosSkill = FindDuplicateVideosSkill()
) {
    suspend fun execute(
        similarityThreshold: Float = FindDuplicateVideosSkill.DEFAULT_SIMILARITY_THRESHOLD,
        durationToleranceMs: Long = FindDuplicateVideosSkill.DEFAULT_DURATION_TOLERANCE_MS
    ): List<DuplicateVideoGroup> = skill.execute(
        FindDuplicateVideosSkill.Input(
            videos = videoRepository.getAllVideos(),
            similarityThreshold = similarityThreshold,
            durationToleranceMs = durationToleranceMs
        )
    )
}
