package com.example.isip.domain.skill

import com.example.isip.data.model.DuplicateGroup
import com.example.isip.data.model.Photo

/**
 * 查找候选重复照片。
 *
 * 优先使用已缓存的 CLIP 图像 embedding；模型不可用时只回退到文件名、尺寸和字节数
 * 都一致的保守匹配，不会把视觉相近的不同照片误报为可清理重复项。
 */
class FindDuplicatesSkill(
    private val similarityEngine: SimilarityEngine? = null
) : Skill<FindDuplicatesSkill.Input, List<DuplicateGroup>> {

    data class Input(
        val photos: List<Photo>,
        val threshold: Float = DEFAULT_THRESHOLD,
        val contentHashes: Map<String, String> = emptyMap()
    )

    data class SimilarPair(
        val firstPhotoId: String,
        val secondPhotoId: String,
        val similarity: Float
    )

    fun interface SimilarityEngine {
        /** 从已有图像向量中找相似对，不应重新解码图片。 */
        suspend fun findSimilar(photoIds: Set<String>, threshold: Float): List<SimilarPair>
    }

    override suspend fun execute(input: Input): List<DuplicateGroup> {
        require(input.threshold in 0f..1f) { "重复阈值必须在 0..1 之间" }
        val photos = input.photos.distinctBy(Photo::id)
        if (photos.size < 2) return emptyList()

        val photoById = photos.associateBy(Photo::id)
        val ids = photoById.keys
        val embeddingPairs = runCatching {
            similarityEngine?.findSimilar(ids, input.threshold).orEmpty()
        }.getOrDefault(emptyList())
            .filter { pair ->
                pair.firstPhotoId in ids && pair.secondPhotoId in ids &&
                    pair.firstPhotoId != pair.secondPhotoId && pair.similarity >= input.threshold
            }
            .map { it.copy(similarity = it.similarity.coerceIn(0f, 1f)) }
        val hashPairs = exactContentPairs(photos, input.contentHashes)
        val fallbackPairs = exactMetadataPairs(photos)
        val pairs = (hashPairs + embeddingPairs + fallbackPairs).distinctBy {
            canonicalPairKey(it.firstPhotoId, it.secondPhotoId)
        }
        if (pairs.isEmpty()) return emptyList()

        val parent = ids.associateWith { it }.toMutableMap()
        fun root(id: String): String {
            var current = id
            while (parent.getValue(current) != current) current = parent.getValue(current)
            return current
        }
        pairs.forEach { pair ->
            val first = root(pair.firstPhotoId)
            val second = root(pair.secondPhotoId)
            if (first != second) parent[second] = first
        }

        return ids.groupBy(::root).values
            .filter { it.size > 1 }
            .map { photoIds ->
                val groupPairs = pairs.filter {
                    it.firstPhotoId in photoIds && it.secondPhotoId in photoIds
                }
                DuplicateGroup(
                    photoIds = photoIds.sorted(),
                    // 组内只要存在一条低于 100% 的连接，就不能把整个组标成内容完全相同。
                    similarity = groupPairs.minOf { it.similarity },
                    recommendKeep = recommendKeep(photoIds, photoById)
                )
            }
            .sortedByDescending(DuplicateGroup::similarity)
    }

    private fun exactMetadataPairs(photos: List<Photo>): List<SimilarPair> = buildList {
        photos.groupBy { photo ->
            "${photo.fileName.lowercase()}|${photo.sizeBytes}|${photo.width}x${photo.height}"
        }.values.filter { it.size > 1 }.forEach { group ->
            for (first in group.indices) {
                for (second in first + 1 until group.size) {
                    add(SimilarPair(group[first].id, group[second].id, METADATA_EXACT_SCORE))
                }
            }
        }
    }

    private fun exactContentPairs(
        photos: List<Photo>,
        contentHashes: Map<String, String>
    ): List<SimilarPair> = buildList {
        val knownIds = photos.map(Photo::id).toSet()
        contentHashes.filterKeys { it in knownIds }
            .filterValues(String::isNotBlank)
            .entries.groupBy({ it.value }, { it.key })
            .values.filter { it.size > 1 }
            .forEach { ids ->
                for (first in ids.indices) for (second in first + 1 until ids.size) {
                    add(SimilarPair(ids[first], ids[second], CONTENT_EXACT_SCORE))
                }
            }
    }

    private fun canonicalPairKey(first: String, second: String): String =
        listOf(first, second).sorted().joinToString("|")

    private fun recommendKeep(photoIds: List<String>, photos: Map<String, Photo>): String =
        photoIds.maxWithOrNull(
            compareBy<String> { id ->
                val photo = photos.getValue(id)
                photo.width.toLong() * photo.height.toLong()
            }.thenBy { id -> photos.getValue(id).sizeBytes }
                .thenBy { id -> photos.getValue(id).dateModified }
        ) ?: photoIds.first()

    override fun getToolDescription(): String = """
        |## 工具名称
        |find_duplicates
        |
        |## 功能
        |使用已生成的 CLIP 图像向量查找候选重复照片并推荐保留质量更高的一张。模型不可用时只返回严格的元数据重复候选；不会自动删除任何照片。
        |
        |## 输入参数
        |- photo_ids (Array<String>, 可选): 待检测范围
        |- threshold (Float, 可选): CLIP 高度重复阈值，默认 0.94
        |
        |## 输出
        |DuplicateGroup[]：photoIds、similarity、recommendKeep。
    """.trimMargin()

    companion object {
        const val DEFAULT_THRESHOLD = 0.94f
        private const val CONTENT_EXACT_SCORE = 1f
        private const val METADATA_EXACT_SCORE = 0.99f
    }
}
