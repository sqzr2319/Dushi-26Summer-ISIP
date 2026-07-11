package com.example.isip.domain.skill

import com.example.isip.data.model.ImageAnalysisResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPhotosSkillTest {

    @Test
    fun `lexical search ranks matching tag and OCR`() = runBlocking {
        val skill = SearchPhotosSkill()

        val result = skill.execute(
            SearchPhotosSkill.Input(
                query = "哈尔滨冬天",
                analyses = listOf(
                    analysis(
                        id = "travel",
                        tags = listOf("#旅行", "#冬天"),
                        ocr = "哈尔滨 2025.12",
                        description = "雪地里的家庭合影"
                    ),
                    analysis(
                        id = "receipt",
                        tags = listOf("#票据"),
                        ocr = "超市小票",
                        description = "一张购物小票"
                    )
                )
            )
        )

        assertEquals(1, result.totalCount)
        assertEquals("travel", result.results.single().photoId)
        assertTrue("#冬天" in result.results.single().matchedTags)
        assertEquals("哈尔滨 2025.12", result.results.single().matchedText)
    }

    @Test
    fun `clip can recall a photo without lexical overlap`() = runBlocking {
        val clip = SearchPhotosSkill.ClipSearchEngine { _, candidateIds, _ ->
            assertTrue("sunset" in candidateIds)
            listOf(SearchPhotosSkill.ClipMatch("sunset", 0.88f, "beach sunset"))
        }
        val skill = SearchPhotosSkill(clipSearchEngine = clip)

        val result = skill.execute(
            SearchPhotosSkill.Input(
                query = "golden hour by the sea",
                analyses = listOf(analysis(id = "sunset"))
            )
        )

        assertEquals("sunset", result.results.single().photoId)
        assertEquals(0.88f, result.results.single().relevanceScore, 0.0001f)
        assertEquals("beach sunset", result.results.single().matchedText)
    }

    @Test
    fun `qwen expansion is sent to clip and original query remains fallback`() = runBlocking {
        val seenQueries = mutableListOf<String>()
        val expander = SearchPhotosSkill.QueryExpander {
            SearchPhotosSkill.QueryExpansion(
                semanticQueries = listOf("a dog running on grass"),
                keywords = listOf("狗", "草地")
            )
        }
        val clip = SearchPhotosSkill.ClipSearchEngine { query, _, _ ->
            seenQueries += query
            if (query == "a dog running on grass") {
                listOf(SearchPhotosSkill.ClipMatch("dog", 0.9f))
            } else {
                emptyList()
            }
        }
        val skill = SearchPhotosSkill(clipSearchEngine = clip, queryExpander = expander)

        val result = skill.execute(
            SearchPhotosSkill.Input(
                query = "草地上奔跑的狗",
                analyses = listOf(analysis(id = "dog"))
            )
        )

        assertTrue("草地上奔跑的狗" in seenQueries)
        assertTrue("a dog running on grass" in seenQueries)
        assertEquals("dog", result.results.single().photoId)
    }

    @Test
    fun `model failure falls back to local matching`() = runBlocking {
        val skill = SearchPhotosSkill(
            clipSearchEngine = SearchPhotosSkill.ClipSearchEngine { _, _, _ ->
                error("model unavailable")
            },
            queryExpander = SearchPhotosSkill.QueryExpander {
                error("qwen unavailable")
            }
        )

        val result = skill.execute(
            SearchPhotosSkill.Input(
                query = "发票",
                analyses = listOf(analysis(id = "invoice", tags = listOf("#发票")))
            )
        )

        assertEquals("invoice", result.results.single().photoId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank query is rejected`() = runBlocking {
        SearchPhotosSkill().execute(
            SearchPhotosSkill.Input(query = "   ", analyses = listOf(analysis("photo")))
        )
    }

    private fun analysis(
        id: String,
        tags: List<String> = emptyList(),
        ocr: String = "",
        description: String = ""
    ) = ImageAnalysisResult(
        photoId = id,
        categories = emptyList(),
        ocrText = ocr,
        tags = tags,
        description = description,
        confidence = 1f,
        analyzedAt = 1L
    )
}
