package com.example.isip.domain.skill

import com.example.isip.data.model.DuplicateVideoGroup
import com.example.isip.data.model.VideoAsset
import kotlin.math.abs

/** Finds duplicate-video candidates without deleting or modifying media. */
class FindDuplicateVideosSkill(
    private val similarityEngine: VideoSimilarityEngine? = null
) : Skill<FindDuplicateVideosSkill.Input, List<DuplicateVideoGroup>> {

    data class Input(
        val videos: List<VideoAsset>,
        val similarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD,
        val durationToleranceMs: Long = DEFAULT_DURATION_TOLERANCE_MS
    )

    data class SimilarPair(
        val firstVideoId: String,
        val secondVideoId: String,
        val similarity: Float,
        val reason: String = "视觉内容相似"
    )

    fun interface VideoSimilarityEngine {
        suspend fun findSimilar(videos: List<VideoAsset>, threshold: Float): List<SimilarPair>
    }

    override suspend fun execute(input: Input): List<DuplicateVideoGroup> {
        require(input.similarityThreshold in 0f..1f) { "similarityThreshold 必须在 0..1 之间" }
        require(input.durationToleranceMs >= 0) { "durationToleranceMs 不能为负数" }

        val videos = input.videos.distinctBy(VideoAsset::id).filter { it.id.isNotBlank() }
        if (videos.size < 2) return emptyList()
        val byId = videos.associateBy(VideoAsset::id)
        val exactPairs = exactHashPairs(videos)
        val enginePairs = runCatching {
            similarityEngine?.findSimilar(videos, input.similarityThreshold).orEmpty()
        }.getOrDefault(emptyList())
        val metadataPairs = metadataPairs(videos, input.durationToleranceMs)

        val validPairs = (exactPairs + enginePairs + metadataPairs)
            .filter { pair ->
                pair.firstVideoId != pair.secondVideoId &&
                    pair.firstVideoId in byId && pair.secondVideoId in byId &&
                    pair.similarity >= input.similarityThreshold
            }
            .distinctBy { pair -> setOf(pair.firstVideoId, pair.secondVideoId) }
        if (validPairs.isEmpty()) return emptyList()

        val parent = videos.associate { it.id to it.id }.toMutableMap()
        fun root(id: String): String {
            var current = id
            while (parent.getValue(current) != current) current = parent.getValue(current)
            var node = id
            while (parent.getValue(node) != node) {
                val next = parent.getValue(node)
                parent[node] = current
                node = next
            }
            return current
        }
        validPairs.forEach { pair ->
            val left = root(pair.firstVideoId)
            val right = root(pair.secondVideoId)
            if (left != right) parent[right] = left
        }

        return videos.groupBy { root(it.id) }.values
            .filter { it.size > 1 }
            .map { group -> buildGroup(group, validPairs) }
            .sortedByDescending(DuplicateVideoGroup::reclaimableBytes)
    }

    private fun exactHashPairs(videos: List<VideoAsset>): List<SimilarPair> = videos
        .filter { !it.contentHash.isNullOrBlank() }
        .groupBy { it.contentHash }
        .values
        .filter { it.size > 1 }
        .flatMap { group ->
            group.indices.flatMap { left ->
                (left + 1 until group.size).map { right ->
                    SimilarPair(group[left].id, group[right].id, 1f, "内容哈希完全一致")
                }
            }
        }

    /** Conservative metadata fallback; these remain review candidates, never automatic deletes. */
    private fun metadataPairs(videos: List<VideoAsset>, durationToleranceMs: Long): List<SimilarPair> =
        videos.indices.flatMap { left ->
            (left + 1 until videos.size).mapNotNull { right ->
                val first = videos[left]
                val second = videos[right]
                val sameDimensions = first.width > 0 && first.height > 0 &&
                    first.width == second.width && first.height == second.height
                val closeDuration = first.durationMs > 0 && second.durationMs > 0 &&
                    abs(first.durationMs - second.durationMs) <= durationToleranceMs
                val largestSize = maxOf(first.sizeBytes, second.sizeBytes).coerceAtLeast(1L)
                val closeSize = abs(first.sizeBytes - second.sizeBytes).toDouble() / largestSize <= 0.01
                if (sameDimensions && closeDuration && closeSize) {
                    SimilarPair(first.id, second.id, METADATA_SIMILARITY, "时长、分辨率和文件大小高度接近")
                } else null
            }
        }

    private fun buildGroup(
        videos: List<VideoAsset>,
        pairs: List<SimilarPair>
    ): DuplicateVideoGroup {
        val ids = videos.map(VideoAsset::id).sorted()
        val related = pairs.filter { it.firstVideoId in ids && it.secondVideoId in ids }
        val keep = videos.maxWithOrNull(
            compareBy<VideoAsset> { it.width.toLong() * it.height }
                .thenBy { bitrateProxy(it) }
                .thenBy(VideoAsset::sizeBytes)
        ) ?: videos.first()
        val total = videos.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        return DuplicateVideoGroup(
            id = "video_duplicates_${ids.joinToString("|").hashCode().toUInt().toString(16)}",
            videoIds = ids,
            recommendKeep = keep.id,
            similarity = related.minOfOrNull(SimilarPair::similarity)?.coerceIn(0f, 1f) ?: 1f,
            totalBytes = total,
            reclaimableBytes = (total - keep.sizeBytes).coerceAtLeast(0L),
            reason = related.maxByOrNull(SimilarPair::similarity)?.reason ?: "候选重复视频"
        )
    }

    private fun bitrateProxy(video: VideoAsset): Long =
        if (video.durationMs > 0) video.sizeBytes * 1000L / video.durationMs else 0L

    override fun getToolDescription(): String = """
        |## 工具名称
        |find_duplicate_videos
        |## 功能
        |根据内容哈希、可选视觉相似度和保守的媒体元数据生成重复视频候选组。只生成候选，不删除文件。
        |## 输出
        |DuplicateVideoGroup 列表，包含推荐保留项、相似度和预计可释放空间。
    """.trimMargin()

    companion object {
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.94f
        const val DEFAULT_DURATION_TOLERANCE_MS = 1_500L
        private const val METADATA_SIMILARITY = 0.95f
    }
}
