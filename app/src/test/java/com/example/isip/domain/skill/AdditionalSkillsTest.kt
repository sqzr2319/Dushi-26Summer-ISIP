package com.example.isip.domain.skill

import com.example.isip.data.model.Photo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdditionalSkillsTest {

    @Test
    fun `semantic search fuses clip score with local tag match`() = runBlocking {
        val skill = SemanticSearchSkill(
            SearchPhotosSkill.ClipSearchEngine { query, candidates, _ ->
                assertEquals("海边日落", query)
                assertTrue("sunset" in candidates)
                listOf(SearchPhotosSkill.ClipMatch("sunset", 0.8f, "beach sunset"))
            }
        )

        val result = skill.execute(
            SemanticSearchSkill.Input(
                query = "海边日落",
                analyses = listOf(analysis("sunset", tags = listOf("#日落")))
            )
        )

        assertEquals("sunset", result.results.single().photoId)
        assertTrue(result.results.single().relevanceScore > 0.6f)
        assertTrue("#日落" in result.results.single().matchedTags)
    }

    @Test
    fun `semantic search falls back to local tags without clip`() = runBlocking {
        val result = SemanticSearchSkill().execute(
            SemanticSearchSkill.Input(
                query = "发票",
                analyses = listOf(analysis("invoice", tags = listOf("#发票")))
            )
        )

        assertEquals(1, result.totalCount)
        assertEquals("invoice", result.results.single().photoId)
    }

    @Test
    fun `add tag normalizes input and reports missing photos`() = runBlocking {
        val skill = AddTagSkill(
            AddTagSkill.TagStore { photoIds, tags, source ->
                assertEquals(listOf("one", "missing"), photoIds)
                assertEquals(listOf("#旅行", "#风景"), tags)
                assertEquals("user", source)
                listOf("one")
            }
        )

        val output = skill.execute(
            AddTagSkill.Input(
                photoIds = listOf("one", "missing", "one"),
                tags = listOf("旅行", "#旅行", " 风景 ")
            )
        )

        assertEquals(listOf("one"), output.updatedPhotoIds)
        assertEquals(listOf("missing"), output.skippedPhotoIds)
    }

    @Test
    fun `create smart album saves a normalized local rule`() = runBlocking {
        val skill = CreateSmartAlbumSkill(
            CreateSmartAlbumSkill.AlbumStore { album -> album.copy(id = 42L) }
        )

        val album = skill.execute(
            CreateSmartAlbumSkill.Input(
                name = "  夏日   旅行 ",
                photoIds = listOf("a", "a", "b"),
                categories = listOf("风景"),
                tags = listOf("海边", "#日落"),
                matchAllTags = true
            )
        )

        assertEquals(42L, album.id)
        assertEquals("夏日 旅行", album.name)
        assertEquals(listOf("a", "b"), album.rule.photoIds)
        assertEquals(listOf("#海边", "#日落"), album.rule.tags)
        assertTrue(album.rule.matchAllTags)
    }

    @Test
    fun `duplicate skill groups clip matches and keeps best resolution`() = runBlocking {
        val skill = FindDuplicatesSkill(
            FindDuplicatesSkill.SimilarityEngine { _, _ ->
                listOf(FindDuplicatesSkill.SimilarPair("a", "b", 0.97f))
            }
        )

        val groups = skill.execute(
            FindDuplicatesSkill.Input(
                photos = listOf(
                    photo("a", width = 100, height = 100, size = 10),
                    photo("b", width = 200, height = 100, size = 20),
                    photo("c", width = 100, height = 100, size = 30)
                )
            )
        )

        assertEquals(1, groups.size)
        assertEquals(listOf("a", "b"), groups.single().photoIds)
        assertEquals("b", groups.single().recommendKeep)
    }

    @Test
    fun `find similar photos excludes target and caps results`() = runBlocking {
        val skill = FindSimilarPhotosSkill(
            FindSimilarPhotosSkill.SimilarityEngine { target, candidates, _ ->
                assertEquals("a", target)
                assertTrue("a" !in candidates)
                listOf(
                    FindSimilarPhotosSkill.SimilarPhoto("b", 0.88f),
                    FindSimilarPhotosSkill.SimilarPhoto("c", 0.77f)
                )
            }
        )

        val output = skill.execute(
            FindSimilarPhotosSkill.Input(
                targetPhotoId = "a",
                photos = listOf(photo("a"), photo("b"), photo("c")),
                topK = 1
            )
        )

        assertEquals(listOf("b"), output.matches.map { it.photoId })
    }

    private fun analysis(id: String, tags: List<String>) = com.example.isip.data.model.ImageAnalysisResult(
        photoId = id,
        categories = emptyList(),
        ocrText = "",
        tags = tags,
        description = "",
        confidence = 1f,
        analyzedAt = 1L
    )

    private fun photo(
        id: String,
        width: Int = 100,
        height: Int = 100,
        size: Long = 10L
    ) = Photo(
        id = id,
        filePath = "content://photo/$id",
        fileName = "$id.jpg",
        dateTaken = 1L,
        dateModified = 1L,
        latitude = null,
        longitude = null,
        sizeBytes = size,
        width = width,
        height = height
    )
}
