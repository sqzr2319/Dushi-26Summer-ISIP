package com.example.isip.ui.organize

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.isip.data.PhotoRepository
import com.example.isip.data.ai.MobileClipProvider
import com.example.isip.data.media.MediaStorePhotoDeletionGateway
import com.example.isip.data.model.CleanupReview
import com.example.isip.data.model.OrganizationPlan
import com.example.isip.domain.skill.CreateSmartAlbumSkill
import com.example.isip.domain.skill.DeletePhotoSkill
import com.example.isip.domain.skill.GenerateStrategySkill
import com.example.isip.domain.skill.ReviewCleanupSkill
import com.example.isip.domain.usecase.CleanupCoordinatorUseCase
import com.example.isip.domain.usecase.AddTagUseCase
import com.example.isip.domain.usecase.OrganizePhotosUseCase
import com.example.isip.domain.usecase.SmartAlbumUseCase
import com.example.isip.ui.model.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrganizeUiState(
    val plan: OrganizationPlanUiModel? = null,
    val sourcePlan: OrganizationPlan? = null,
    val smartAlbums: List<SmartAlbumUiModel> = emptyList(),
    val selectedSuggestionIds: Set<String> = emptySet(),
    val isGeneratingPlan: Boolean = false,
    val actionPreview: BatchActionPreviewUi? = null,
    val pendingTagSuggestion: TagSuggestionUi? = null,
    val duplicateComparison: DuplicateComparisonUi? = null,
    val similarComparison: SimilarGroupUi? = null,
    val cleanupReview: CleanupReview? = null,
    val pendingDeleteRequest: DeletePhotoSkill.DeleteRequest? = null,
    val launchedDeleteRequestId: String? = null,
    val isExecutingCleanup: Boolean = false,
    val completionMessage: String? = null,
    val errorMessage: String? = null
)

sealed interface OrganizeUiEvent {
    data object GeneratePlan : OrganizeUiEvent
    data class ToggleSuggestion(val suggestionId: String) : OrganizeUiEvent
    data object PreviewSelected : OrganizeUiEvent
    data object ConfirmSelected : OrganizeUiEvent
    data object DismissActionPreview : OrganizeUiEvent
    data class PreviewTagSuggestion(val suggestion: TagSuggestionUi) : OrganizeUiEvent
    data object ConfirmTagSuggestion : OrganizeUiEvent
    data object DismissTagSuggestion : OrganizeUiEvent
    data class OpenDuplicateComparison(val group: DuplicateGroup) : OrganizeUiEvent
    data class SelectDuplicateKeep(val photoId: String) : OrganizeUiEvent
    data object ConfirmDuplicateComparison : OrganizeUiEvent
    data object DismissDuplicateComparison : OrganizeUiEvent
    data class OpenSimilarComparison(val group: SimilarGroupUi) : OrganizeUiEvent
    data object DismissSimilarComparison : OrganizeUiEvent
    data class ReviewDuplicateCleanup(
        val selectedCandidateIds: Set<String>? = null,
        val keepOverrides: Map<String, String> = emptyMap(),
        val protectedPhotoIds: Set<String> = emptySet()
    ) : OrganizeUiEvent
    data object RequestReviewedDeletion : OrganizeUiEvent
    data class DeleteConfirmationLaunched(val requestId: String) : OrganizeUiEvent
    data class CleanupConfirmationResult(
        val requestId: String,
        val approved: Boolean,
        val systemDeleteCompleted: Boolean
    ) : OrganizeUiEvent
    data object DismissCleanupReview : OrganizeUiEvent
    data object DismissMessage : OrganizeUiEvent
}

class OrganizeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PhotoRepository.getInstance(application)
    private val deletePhotoSkill = DeletePhotoSkill(MediaStorePhotoDeletionGateway(application, repository))
    private val cleanupCoordinator = CleanupCoordinatorUseCase(ReviewCleanupSkill(), deletePhotoSkill)
    private val organizeUseCase = OrganizePhotosUseCase(
        repository,
        // 整理页不能在主线程初始化约 400MB 的模型；仅复用之前分析阶段已加载的实例。
        GenerateStrategySkill(clipSimilarityEngine = MobileClipProvider.peekOrNull()),
        cleanupCoordinator
    )
    private val smartAlbumUseCase = SmartAlbumUseCase(repository)
    private val addTagUseCase = AddTagUseCase(repository)
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var organizationPlan: OrganizationPlan? = null

    private val _uiState = MutableStateFlow(OrganizeUiState())
    val uiState: StateFlow<OrganizeUiState> = _uiState.asStateFlow()

    init { loadOrganizationPlan() }

    fun onEvent(event: OrganizeUiEvent) {
        when (event) {
            OrganizeUiEvent.GeneratePlan -> loadOrganizationPlan()
            is OrganizeUiEvent.ToggleSuggestion -> toggleSuggestion(event.suggestionId)
            OrganizeUiEvent.PreviewSelected -> previewSelected()
            OrganizeUiEvent.ConfirmSelected -> confirmSelected()
            OrganizeUiEvent.DismissActionPreview -> _uiState.update { it.copy(actionPreview = null) }
            is OrganizeUiEvent.PreviewTagSuggestion -> _uiState.update {
                it.copy(pendingTagSuggestion = event.suggestion)
            }
            OrganizeUiEvent.ConfirmTagSuggestion -> confirmTagSuggestion()
            OrganizeUiEvent.DismissTagSuggestion -> _uiState.update { it.copy(pendingTagSuggestion = null) }
            is OrganizeUiEvent.OpenDuplicateComparison -> _uiState.update {
                it.copy(
                    duplicateComparison = DuplicateComparisonUi(
                        event.group,
                        event.group.recommendedKeepId
                    )
                )
            }
            is OrganizeUiEvent.SelectDuplicateKeep -> _uiState.update { state ->
                val comparison = state.duplicateComparison
                if (comparison != null && comparison.group.photos.any { it.id == event.photoId }) {
                    state.copy(duplicateComparison = comparison.copy(selectedKeepId = event.photoId))
                } else state
            }
            OrganizeUiEvent.ConfirmDuplicateComparison -> confirmDuplicateComparison()
            OrganizeUiEvent.DismissDuplicateComparison -> _uiState.update {
                it.copy(duplicateComparison = null)
            }
            is OrganizeUiEvent.OpenSimilarComparison -> _uiState.update {
                it.copy(similarComparison = event.group)
            }
            OrganizeUiEvent.DismissSimilarComparison -> _uiState.update {
                it.copy(similarComparison = null)
            }
            is OrganizeUiEvent.ReviewDuplicateCleanup -> reviewDuplicateCleanup(event)
            OrganizeUiEvent.RequestReviewedDeletion -> requestReviewedDeletion()
            is OrganizeUiEvent.DeleteConfirmationLaunched -> _uiState.update {
                if (it.pendingDeleteRequest?.requestId == event.requestId) {
                    it.copy(launchedDeleteRequestId = event.requestId)
                } else it
            }
            is OrganizeUiEvent.CleanupConfirmationResult -> confirmCleanupDeletion(event)
            OrganizeUiEvent.DismissCleanupReview -> dismissCleanupReview()
            OrganizeUiEvent.DismissMessage -> _uiState.update {
                it.copy(completionMessage = null, errorMessage = null)
            }
        }
    }

    private fun loadOrganizationPlan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingPlan = true, errorMessage = null) }
            try {
                val plan = organizeUseCase.generateOrganizationPlan()
                organizationPlan = plan
                val savedAlbums = smartAlbumUseCase.getAll()
                val existingAlbumNames = savedAlbums.map { it.name }.toSet()
                val smartAlbums = savedAlbums.map { album ->
                    val albumPhotos = runCatching { smartAlbumUseCase.resolvePhotos(album) }
                        .getOrDefault(emptyList())
                    SmartAlbumUiModel(
                        id = album.id,
                        name = album.name,
                        photos = albumPhotos
                            .take(UI_PREVIEW_LIMIT)
                            .mapNotNull { photoUi(it.id) },
                        photoCount = albumPhotos.size,
                        ruleDescription = buildList {
                            if (album.rule.categories.isNotEmpty()) add("分类：${album.rule.categories.joinToString("、")}")
                            if (album.rule.tags.isNotEmpty()) add("标签：${album.rule.tags.joinToString("、")}")
                            if (album.rule.photoIds.isNotEmpty()) add("固定 ${album.rule.photoIds.size} 张照片")
                        }.joinToString(" · ")
                    )
                }
                val uiPlan = OrganizationPlanUiModel(
                    categorySuggestions = plan.albums.filterNot { it.name in existingAlbumNames }.map { album ->
                        CategorySuggestion(
                            id = album.id,
                            categoryName = album.name,
                            photoCount = album.photoIds.size,
                            description = album.description.orEmpty(),
                            photos = album.photoIds.take(UI_PREVIEW_LIMIT).mapNotNull { photoUi(it) }
                        )
                    },
                    duplicateGroups = plan.duplicates.mapIndexed { index, duplicate ->
                        DuplicateGroup(
                            id = "photo_duplicates_$index",
                            photos = duplicate.photoIds.mapNotNull { photoUi(it) },
                            similarityScore = duplicate.similarity,
                            recommendedKeepId = duplicate.recommendKeep
                        )
                    },
                    similarGroups = plan.similarPhotos.map { group ->
                        SimilarGroupUi(
                            id = group.id,
                            photos = group.photoIds.mapNotNull { photoUi(it) },
                            similarityScore = group.similarity,
                            reason = group.reason
                        )
                    },
                    tagSuggestions = plan.tagSuggestions.mapNotNull { suggestion ->
                        photoUi(suggestion.photoId)?.let {
                            TagSuggestionUi(it, suggestion.tags, suggestion.reason)
                        }
                    },
                    privacyAlerts = plan.privacyRisks.mapIndexed { index, alert ->
                        val photo = photoUi(alert.photoId, hasPrivacyAlert = true)
                            ?: PhotoUiModel(alert.photoId, "", "", hasPrivacyAlert = true, isAnalyzed = true)
                        PrivacyAlert(
                            id = "${alert.photoId}:${alert.privacyType}:$index",
                            photo = photo,
                            alertType = alert.privacyType,
                            description = alert.description,
                            severity = when {
                                alert.privacyType.contains("身份证") || alert.privacyType.contains("银行卡") -> PrivacySeverity.HIGH
                                alert.privacyType.contains("截图") -> PrivacySeverity.MEDIUM
                                else -> PrivacySeverity.LOW
                            }
                        )
                    },
                    suggestions = plan.suggestions
                )
                _uiState.update {
                    it.copy(
                        plan = uiPlan,
                        sourcePlan = plan,
                        smartAlbums = smartAlbums,
                        selectedSuggestionIds = emptySet(),
                        isGeneratingPlan = false
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isGeneratingPlan = false, errorMessage = "生成整理方案失败：${error.message.orEmpty()}")
                }
            }
        }
    }

    private suspend fun photoUi(photoId: String, hasPrivacyAlert: Boolean = false): PhotoUiModel? {
        val photo = repository.getPhotoById(photoId) ?: return null
        val analysis = repository.getAnalysisResult(photo.id)
        return PhotoUiModel(
            id = photo.id,
            uri = repository.getPhotoUri(photo.id).toString(),
            takenAtText = dateFormatter.format(Date(photo.dateTaken)),
            categories = analysis?.categories.orEmpty(),
            tags = analysis?.tags.orEmpty(),
            hasPrivacyAlert = hasPrivacyAlert,
            isAnalyzed = analysis != null,
            sizeBytes = photo.sizeBytes,
            width = photo.width,
            height = photo.height
        )
    }

    private fun confirmDuplicateComparison() {
        val comparison = _uiState.value.duplicateComparison ?: return
        _uiState.update { it.copy(duplicateComparison = null) }
        reviewDuplicateCleanup(
            OrganizeUiEvent.ReviewDuplicateCleanup(
                selectedCandidateIds = setOf(comparison.group.id),
                keepOverrides = mapOf(comparison.group.id to comparison.selectedKeepId)
            )
        )
    }

    private fun toggleSuggestion(id: String) {
        _uiState.update { state ->
            state.copy(selectedSuggestionIds = if (id in state.selectedSuggestionIds) {
                state.selectedSuggestionIds - id
            } else state.selectedSuggestionIds + id)
        }
    }

    private fun previewSelected() {
        val state = _uiState.value
        val plan = state.sourcePlan ?: return
        val selected = plan.albums.filter { it.id in state.selectedSuggestionIds }
        if (selected.isEmpty()) return
        val photos = state.plan?.categorySuggestions
            ?.filter { it.id in state.selectedSuggestionIds }
            ?.flatMap { it.photos }
            ?.distinctBy { it.id }
            .orEmpty()
        _uiState.update {
            it.copy(
                actionPreview = BatchActionPreviewUi(
                    actionType = "创建智能相册",
                    affectedPhotoCount = photos.size,
                    affectedPhotos = photos,
                    description = "将创建 ${selected.size} 个智能相册。此操作只保存分类规则，不会移动或删除原图。"
                )
            )
        }
    }

    private fun confirmSelected() {
        val state = _uiState.value
        val plan = state.sourcePlan ?: return
        val selected = plan.albums.filter { it.id in state.selectedSuggestionIds }
        if (selected.isEmpty() || state.actionPreview == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(actionPreview = null, isExecutingCleanup = true, errorMessage = null) }
            try {
                val existingNames = smartAlbumUseCase.getAll().map { it.name }.toSet()
                selected.filterNot { it.name in existingNames }.forEach { album ->
                    smartAlbumUseCase.create(
                        CreateSmartAlbumSkill.Input(
                            name = album.name,
                            photoIds = album.photoIds,
                            categories = listOf(album.name.removeSuffix(" 相册")),
                            createdBy = "organize"
                        )
                    )
                }
                _uiState.update {
                    it.copy(
                        isExecutingCleanup = false,
                        completionMessage = "已创建 ${selected.size} 个智能相册",
                        selectedSuggestionIds = emptySet()
                    )
                }
                loadOrganizationPlan()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isExecutingCleanup = false, errorMessage = "创建智能相册失败：${error.message.orEmpty()}")
                }
            }
        }
    }

    private fun confirmTagSuggestion() {
        val suggestion = _uiState.value.pendingTagSuggestion ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingTagSuggestion = null, isExecutingCleanup = true, errorMessage = null) }
            try {
                val result = addTagUseCase.addTags(listOf(suggestion.photo.id), suggestion.tags)
                _uiState.update {
                    it.copy(
                        isExecutingCleanup = false,
                        completionMessage = "已为 ${result.updatedPhotoIds.size} 张照片写入 ${result.tags.size} 个标签"
                    )
                }
                loadOrganizationPlan()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isExecutingCleanup = false, errorMessage = "写入标签失败：${error.message.orEmpty()}")
                }
            }
        }
    }

    private fun reviewDuplicateCleanup(event: OrganizeUiEvent.ReviewDuplicateCleanup) {
        val plan = organizationPlan ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isExecutingCleanup = true, errorMessage = null) }
            try {
                val review = organizeUseCase.reviewDuplicateCleanup(
                    plan,
                    event.selectedCandidateIds,
                    event.keepOverrides,
                    event.protectedPhotoIds
                )
                _uiState.update { it.copy(cleanupReview = review, isExecutingCleanup = false) }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isExecutingCleanup = false, errorMessage = "生成删除复核失败：${error.message.orEmpty()}")
                }
            }
        }
    }

    private fun requestReviewedDeletion() {
        if (_uiState.value.pendingDeleteRequest != null) return
        val review = _uiState.value.cleanupReview ?: return
        try {
            val request = organizeUseCase.requestDeleteAfterReview(review)
            _uiState.update {
                it.copy(pendingDeleteRequest = request, launchedDeleteRequestId = null, errorMessage = null)
            }
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = "无法创建删除请求：${error.message.orEmpty()}") }
        }
    }

    private fun confirmCleanupDeletion(event: OrganizeUiEvent.CleanupConfirmationResult) {
        if (_uiState.value.pendingDeleteRequest?.requestId != event.requestId) return
        viewModelScope.launch {
            _uiState.update { it.copy(isExecutingCleanup = true, pendingDeleteRequest = null) }
            try {
                val result = organizeUseCase.confirmCleanupDeletion(
                    event.requestId,
                    event.approved,
                    event.systemDeleteCompleted
                )
                _uiState.update {
                    it.copy(
                        cleanupReview = if (result.cancelled) it.cleanupReview else null,
                        isExecutingCleanup = false,
                        completionMessage = when {
                            result.cancelled -> "已取消删除，照片未发生变化"
                            result.deletedIds.isNotEmpty() -> "已删除 ${result.deletedIds.size} 张照片"
                            else -> null
                        },
                        errorMessage = result.failedIds.takeIf { ids -> ids.isNotEmpty() }
                            ?.let { ids -> "${ids.size} 张照片删除失败" }
                    )
                }
                if (!result.cancelled && result.deletedIds.isNotEmpty()) loadOrganizationPlan()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isExecutingCleanup = false, errorMessage = "执行清理失败：${error.message.orEmpty()}")
                }
            }
        }
    }

    private fun dismissCleanupReview() {
        if (_uiState.value.pendingDeleteRequest == null) {
            _uiState.update { it.copy(cleanupReview = null) }
        }
    }

    private companion object {
        const val UI_PREVIEW_LIMIT = 4
    }
}
