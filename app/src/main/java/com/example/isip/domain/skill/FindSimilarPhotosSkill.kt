package com.example.isip.domain.skill

import com.example.isip.data.model.Photo

/** 根据一张参考照片的 CLIP 图像向量查找视觉相似照片。 */
class FindSimilarPhotosSkill(
    private val similarityEngine: SimilarityEngine? = null
) : Skill<FindSimilarPhotosSkill.Input, FindSimilarPhotosSkill.Output> {

    data class Input(
        val targetPhotoId: String,
        val photos: List<Photo>,
        val topK: Int = DEFAULT_TOP_K,
        val minSimilarity: Float = DEFAULT_MIN_SIMILARITY
    )

    data class SimilarPhoto(
        val photoId: String,
        val similarity: Float,
        val explanation: String = ""
    )

    data class Output(
        val targetPhotoId: String,
        val matches: List<SimilarPhoto>
    )

    fun interface SimilarityEngine {
        /** 基于已缓存 embedding 返回指定照片的相似项。 */
        suspend fun findSimilar(
            targetPhotoId: String,
            candidatePhotoIds: Set<String>,
            limit: Int
        ): List<SimilarPhoto>
    }

    override suspend fun execute(input: Input): Output {
        val photos = input.photos.distinctBy(Photo::id)
        val target = photos.firstOrNull { it.id == input.targetPhotoId }
            ?: throw IllegalArgumentException("目标照片不存在: ${input.targetPhotoId}")
        require(input.topK > 0) { "topK 必须大于 0" }
        require(input.minSimilarity in 0f..1f) { "最小相似度必须在 0..1 之间" }

        val candidates = photos.asSequence().map(Photo::id)
            .filterNot { it == target.id }.toSet()
        val vectorMatches = runCatching {
            similarityEngine?.findSimilar(target.id, candidates, input.topK * CANDIDATE_MULTIPLIER)
                .orEmpty()
        }.getOrDefault(emptyList())
        val fallbackMatches = exactMetadataMatches(target, photos)

        val matches = (vectorMatches + fallbackMatches)
            .asSequence()
            .filter { it.photoId in candidates && it.similarity >= input.minSimilarity }
            .map { it.copy(similarity = it.similarity.coerceIn(0f, 1f)) }
            .groupBy(SimilarPhoto::photoId)
            .map { (_, values) -> values.maxBy(SimilarPhoto::similarity) }
            .sortedByDescending(SimilarPhoto::similarity)
            .take(input.topK)
            .toList()

        return Output(targetPhotoId = target.id, matches = matches)
    }

    private fun exactMetadataMatches(target: Photo, photos: List<Photo>): List<SimilarPhoto> = photos
        .asSequence()
        .filterNot { it.id == target.id }
        .filter {
            it.fileName.equals(target.fileName, ignoreCase = true) &&
                it.sizeBytes == target.sizeBytes &&
                it.width == target.width &&
                it.height == target.height
        }
        .map { SimilarPhoto(it.id, METADATA_EXACT_SCORE, "文件元数据完全一致") }
        .toList()

    override fun getToolDescription(): String = """
        |## 工具名称
        |find_similar_photos
        |
        |## 功能
        |以一张本地照片为参考，从已建立的 CLIP 图像向量索引中查找视觉相似照片。
        |
        |## 输入参数
        |- target_photo_id (String, 必填): 参考照片 ID
        |- top_k (Int, 可选): 最多返回数量，默认 20
        |- min_similarity (Float, 可选): 最低相似度，默认 0.60
        |
        |## 输出
        |targetPhotoId 与 SimilarPhoto[]（photoId、similarity、explanation）。
    """.trimMargin()

    companion object {
        const val DEFAULT_TOP_K = 20
        const val DEFAULT_MIN_SIMILARITY = 0.60f
        const val CANDIDATE_MULTIPLIER = 3
        const val METADATA_EXACT_SCORE = 0.99f
    }
}
