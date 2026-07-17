package com.example.isip.domain.skill

import com.example.isip.data.ai.PhotoContentAnalysis
import com.example.isip.data.ai.PhotoContentAnalyzer
import com.example.isip.data.model.CleanupCandidate
import com.example.isip.data.model.CleanupCandidateType
import com.example.isip.data.model.DuplicateGroup
import com.example.isip.data.model.EventAlbum
import com.example.isip.data.model.ImageAnalysisResult
import com.example.isip.data.model.OrganizationPlan
import com.example.isip.data.model.Photo
import com.example.isip.data.model.SimilarPhotoGroup
import com.example.isip.data.model.TagSuggestion
import com.example.isip.data.model.VideoAsset
import com.example.isip.domain.usecase.CleanupCoordinatorUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSkillsTest {

    @Test
    fun analyzeImageUsesInjectedAnalyzersWithoutKnowingModelImplementation() = runBlocking {
        var detailCalls = 0
        val detail = object : PhotoContentAnalyzer {
            override val modelName = "replaceable-vision-model"
            override val modelVersion = "1"
            override suspend fun analyze(photo: Photo): PhotoContentAnalysis {
                detailCalls++
                return PhotoContentAnalysis(
                    categories = listOf("文档"),
                    tags = listOf("发票"),
                    description = "一张发票",
                    confidence = 0.9f
                )
            }
        }
        val skill = AnalyzeImageSkill(
            clipAnalyzer = AnalyzeImageSkill.ClipImageAnalyzer {
                AnalyzeImageSkill.ClipAnalysis(listOf("风景"), confidence = 0.95f)
            },
            detailAnalyzer = detail
        )

        val coarse = skill.execute(AnalyzeImageSkill.Input(photo("1")))
        assertEquals(0, detailCalls)
        assertTrue("风景" in coarse.categories)

        val detailed = skill.execute(AnalyzeImageSkill.Input(photo("1"), requireDetail = true))
        assertEquals(1, detailCalls)
        assertTrue("文档" in detailed.categories)
        assertTrue("replaceable-vision-model" in detailed.modelName.orEmpty())
    }

    @Test
    fun generateStrategyBuildsAlbumsDuplicatesAndPrivacyWarnings() = runBlocking {
        val skill = GenerateStrategySkill { _, _ ->
            listOf(GenerateStrategySkill.SimilarPair("1", "2", 0.98f))
        }
        val analyses = listOf(
            analysis("1", categories = listOf("旅行"), ocr = "password 123"),
            analysis("2", categories = listOf("旅行"))
        )
        val plan = skill.execute(
            GenerateStrategySkill.Input(analyses, listOf(photo("1", 10), photo("2", 20)))
        )

        assertEquals(1, plan.albums.size)
        assertEquals(setOf("1", "2"), plan.duplicates.single().photoIds.toSet())
        assertEquals("2", plan.duplicates.single().recommendKeep)
        assertTrue(plan.privacyRisks.any { it.photoId == "1" })
    }

    @Test
    fun generateStrategyKeepsStablePortsForCollaboratingSkills() = runBlocking {
        val skill = GenerateStrategySkill(
            semanticSearchSkillPort = GenerateStrategySkill.SemanticSearchSkillPort {
                listOf(GenerateStrategySkill.SemanticGroup("trip", "旅行", listOf("1", "2")))
            },
            createSmartAlbumSkillPort = GenerateStrategySkill.CreateSmartAlbumSkillPort { input ->
                listOf(
                    EventAlbum(
                        id = "album-trip",
                        name = "旅行相册",
                        eventDate = null,
                        coverPhotoId = input.semanticGroups.first().photoIds.first(),
                        photoIds = input.semanticGroups.first().photoIds,
                        description = "协作端口生成"
                    )
                )
            },
            addTagSkillPort = GenerateStrategySkill.AddTagSkillPort {
                listOf(TagSuggestion("1", listOf("银行卡", "#旅行")))
            },
            findDuplicatesSkillPort = GenerateStrategySkill.FindDuplicatesSkillPort {
                listOf(DuplicateGroup(listOf("1", "2"), 0.99f, "2"))
            },
            findSimilarPhotosSkillPort = GenerateStrategySkill.FindSimilarPhotosSkillPort {
                listOf(SimilarPhotoGroup("similar", listOf("1", "2"), 0.88f, "场景相似"))
            }
        )

        val plan = skill.execute(
            GenerateStrategySkill.Input(
                analyses = listOf(analysis("1"), analysis("2")),
                photos = listOf(photo("1", 10), photo("2", 20))
            )
        )

        assertEquals("album-trip", plan.albums.single().id)
        assertEquals("2", plan.duplicates.single().recommendKeep)
        assertEquals("similar", plan.similarPhotos.single().id)
        assertEquals(listOf("#银行卡", "#旅行"), plan.tagSuggestions.single().tags)
        assertTrue(plan.privacyRisks.any { it.photoId == "1" && it.privacyType == "银行卡" })
    }

    @Test
    fun findDuplicateVideosCombinesTransitivePairsAndKeepsBestQuality() = runBlocking {
        val skill = FindDuplicateVideosSkill { _, _ ->
            listOf(
                FindDuplicateVideosSkill.SimilarPair("a", "b", 0.98f),
                FindDuplicateVideosSkill.SimilarPair("b", "c", 0.96f)
            )
        }
        val videos = listOf(
            video("a", 1280, 720, 10),
            video("b", 1920, 1080, 20),
            video("c", 1280, 720, 12)
        )
        val group = skill.execute(FindDuplicateVideosSkill.Input(videos)).single()

        assertEquals(setOf("a", "b", "c"), group.videoIds.toSet())
        assertEquals("b", group.recommendKeep)
        assertEquals(0.96f, group.similarity)
        assertEquals(22L, group.reclaimableBytes)
    }

    @Test
    fun reviewCleanupProtectsFilesAndRequiresKeepForDuplicateGroups() = runBlocking {
        val candidates = listOf(
            CleanupCandidate(
                id = "duplicates",
                type = CleanupCandidateType.DUPLICATE_PHOTO,
                mediaIds = listOf("a", "b", "c"),
                recommendedKeepId = "a",
                reason = "重复照片",
                confidence = 0.98f
            ),
            CleanupCandidate(
                id = "screenshot",
                type = CleanupCandidateType.SCREENSHOT,
                mediaIds = listOf("d"),
                reason = "旧截图",
                confidence = 0.8f
            )
        )
        val review = ReviewCleanupSkill().execute(
            ReviewCleanupSkill.Input(
                candidates = candidates,
                protectedMediaIds = setOf("c"),
                mediaSizeBytes = mapOf("a" to 10, "b" to 20, "c" to 30, "d" to 40)
            )
        )

        assertEquals(setOf("b", "d"), review.deleteIds.toSet())
        assertEquals(60L, review.reclaimableBytes)
        assertTrue(review.requiresUserConfirmation)
        assertTrue(review.warnings.isNotEmpty())
    }

    @Test
    fun deletePhotoRequiresMatchingSingleUseConfirmation() = runBlocking {
        var gatewayCalls = 0
        val skill = DeletePhotoSkill(
            deletionGateway = DeletePhotoSkill.ConfirmedPhotoDeletionGateway { ids ->
                gatewayCalls++
                DeletePhotoSkill.DeleteResult(ids, ids, emptyList())
            },
            requestIdFactory = { "request-1" }
        )
        val request = skill.execute(DeletePhotoSkill.Input(listOf("1", "1", "2")))
        assertEquals(listOf("1", "2"), request.photoIds)
        assertEquals(0, gatewayCalls)

        val cancelled = skill.confirm(DeletePhotoSkill.Confirmation(request.requestId, approved = false))
        assertTrue(cancelled.cancelled)
        assertEquals(0, gatewayCalls)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { skill.confirm(DeletePhotoSkill.Confirmation(request.requestId, true)) }
        }

        val second = skill.execute(DeletePhotoSkill.Input(listOf("1", "2")))
        val deleted = skill.confirm(DeletePhotoSkill.Confirmation(second.requestId, approved = true))
        assertEquals(1, gatewayCalls)
        assertEquals(listOf("1", "2"), deleted.deletedIds)
    }

    @Test
    fun cleanupCoordinatorReviewsThenDeletesOnlyConfirmedIds() = runBlocking {
        var deletedByGateway = emptyList<String>()
        val deleteSkill = DeletePhotoSkill(
            deletionGateway = DeletePhotoSkill.ConfirmedPhotoDeletionGateway { ids ->
                deletedByGateway = ids
                DeletePhotoSkill.DeleteResult(ids, ids, emptyList())
            },
            requestIdFactory = { "cleanup-request" }
        )
        val coordinator = CleanupCoordinatorUseCase(ReviewCleanupSkill(), deleteSkill)
        val plan = OrganizationPlan(
            albums = emptyList(),
            duplicates = listOf(DuplicateGroup(listOf("a", "b", "c"), 0.98f, "a")),
            privacyRisks = emptyList(),
            suggestions = emptyList()
        )

        val review = coordinator.reviewPhotoDuplicates(
            plan = plan,
            photos = listOf(photo("a", 10), photo("b", 20), photo("c", 30)),
            protectedPhotoIds = setOf("c")
        )
        assertEquals(listOf("b"), review.deleteIds)

        val request = coordinator.requestDeleteAfterReview(review)
        assertTrue(deletedByGateway.isEmpty())
        val result = coordinator.confirmDeletion(request.requestId, approved = true)

        assertEquals(listOf("b"), deletedByGateway)
        assertEquals(listOf("b"), result.deletedIds)
    }

    @Test
    fun summarizeSelectionUsesLatestAnalysisAndLocalMetadata() = runBlocking {
        val photos = listOf(
            photo("1", size = 1_048_576, width = 200, height = 100, taken = 100),
            photo("2", size = 2_097_152, width = 100, height = 200, taken = 200),
            photo("2", size = 99, width = 1, height = 1, taken = 300)
        )
        val analyses = listOf(
            analysis("1", categories = listOf("旅行"), analyzedAt = 1),
            analysis("1", categories = listOf("风景"), analyzedAt = 2, ocr = "银行卡"),
            analysis("missing", categories = listOf("无关"))
        )
        val summary = SummarizeSelectionSkill().execute(
            SummarizeSelectionSkill.Input(photos, analyses)
        )

        assertEquals(2, summary.count)
        assertEquals(3_145_728L, summary.totalBytes)
        assertEquals(mapOf("风景" to 1), summary.categoryCounts)
        assertEquals(1, summary.analyzedCount)
        assertEquals(1, summary.unanalyzedCount)
        assertEquals(1, summary.privacyRiskCount)
        assertEquals(1, summary.landscapeCount)
        assertEquals(1, summary.portraitCount)
        assertFalse(summary.text.isBlank())
    }

    private fun photo(
        id: String,
        size: Long = 1,
        width: Int = 100,
        height: Int = 100,
        taken: Long = 1
    ) = Photo(id, "content://photo/$id", "$id.jpg", taken, taken, null, null, size, width, height)

    private fun analysis(
        id: String,
        categories: List<String> = emptyList(),
        ocr: String = "",
        analyzedAt: Long = 1
    ) = ImageAnalysisResult(
        photoId = id,
        categories = categories,
        ocrText = ocr,
        tags = emptyList(),
        description = "",
        confidence = 0.9f,
        analyzedAt = analyzedAt
    )

    private fun video(id: String, width: Int, height: Int, size: Long) = VideoAsset(
        id = id,
        uri = "content://video/$id",
        displayName = "$id.mp4",
        sizeBytes = size,
        durationMs = 10_000,
        width = width,
        height = height,
        dateTaken = 1
    )
}
