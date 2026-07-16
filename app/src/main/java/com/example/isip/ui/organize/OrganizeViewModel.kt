package com.example.isip.ui.organize

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.isip.data.PhotoRepository
import com.example.isip.data.media.MediaStorePhotoDeletionGateway
import com.example.isip.data.model.CleanupReview
import com.example.isip.data.model.OrganizationPlan
import com.example.isip.domain.usecase.OrganizePhotosUseCase
import com.example.isip.domain.usecase.CleanupCoordinatorUseCase
import com.example.isip.data.ai.MobileClipProvider
import com.example.isip.domain.skill.DeletePhotoSkill
import com.example.isip.domain.skill.GenerateStrategySkill
import com.example.isip.domain.skill.ReviewCleanupSkill
import com.example.isip.ui.model.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OrganizeUiState(
    val plan: OrganizationPlanUiModel? = null,
    val selectedSuggestionIds: Set<String> = emptySet(),
    val isGeneratingPlan: Boolean = false,
    val actionPreview: BatchActionPreviewUi? = null,
    val cleanupReview: CleanupReview? = null,
    val pendingDeleteRequest: DeletePhotoSkill.DeleteRequest? = null,
    val isExecutingCleanup: Boolean = false,
    val lastActionUndoable: Boolean = false,
    val errorMessage: String? = null
)

sealed interface OrganizeUiEvent {
    data object GeneratePlan : OrganizeUiEvent
    data class ToggleSuggestion(val suggestionId: String) : OrganizeUiEvent
    data object ExecuteSelected : OrganizeUiEvent
    data class ReviewDuplicateCleanup(
        val selectedCandidateIds: Set<String>? = null,
        val keepOverrides: Map<String, String> = emptyMap(),
        val protectedPhotoIds: Set<String> = emptySet()
    ) : OrganizeUiEvent
    data object RequestReviewedDeletion : OrganizeUiEvent
    data class CleanupConfirmationResult(
        val requestId: String,
        val approved: Boolean,
        val systemDeleteCompleted: Boolean
    ) : OrganizeUiEvent
    data object DismissCleanupReview : OrganizeUiEvent
    data object UndoLastAction : OrganizeUiEvent
}

class OrganizeViewModel(application: Application) : AndroidViewModel(application) {

    // 初始化 Repository 和 UseCase
    private val repository = PhotoRepository.getInstance(application)
    private val deletePhotoSkill = DeletePhotoSkill(
        MediaStorePhotoDeletionGateway(application, repository)
    )
    private val cleanupCoordinator = CleanupCoordinatorUseCase(
        ReviewCleanupSkill(),
        deletePhotoSkill
    )
    private val organizeUseCase = OrganizePhotosUseCase(
        repository,
        GenerateStrategySkill(
            clipSimilarityEngine = MobileClipProvider.getOrNull(application)
        ),
        cleanupCoordinator
    )
    private var organizationPlan: OrganizationPlan? = null

    private val _uiState = MutableStateFlow(OrganizeUiState())
    val uiState: StateFlow<OrganizeUiState> = _uiState.asStateFlow()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    init {
        loadOrganizationPlan()
    }

    fun onEvent(event: OrganizeUiEvent) {
        when (event) {
            is OrganizeUiEvent.GeneratePlan -> loadOrganizationPlan()
            is OrganizeUiEvent.ToggleSuggestion -> toggleSuggestion(event.suggestionId)
            is OrganizeUiEvent.ExecuteSelected -> executeSelected()
            is OrganizeUiEvent.ReviewDuplicateCleanup -> reviewDuplicateCleanup(event)
            is OrganizeUiEvent.RequestReviewedDeletion -> requestReviewedDeletion()
            is OrganizeUiEvent.CleanupConfirmationResult -> confirmCleanupDeletion(event)
            is OrganizeUiEvent.DismissCleanupReview -> dismissCleanupReview()
            is OrganizeUiEvent.UndoLastAction -> undoLastAction()
        }
    }

    private fun loadOrganizationPlan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingPlan = true, errorMessage = null) }

            try {
                // 调用真实的整理 UseCase
                val plan = organizeUseCase.generateOrganizationPlan()
                organizationPlan = plan

                // 转换为 UI 模型
                val uiPlan = OrganizationPlanUiModel(
                    categorySuggestions = plan.albums.map { album ->
                        CategorySuggestion(
                            id = album.id,
                            categoryName = album.name,
                            photoCount = album.photoIds.size,
                            description = album.description ?: ""
                        )
                    },
                    duplicateGroups = plan.duplicates.mapIndexed { index, duplicate ->
                        val photos = duplicate.photoIds.mapNotNull { photoId ->
                            val photo = repository.getPhotoById(photoId)
                            photo?.let {
                                PhotoUiModel(
                                    id = it.id,
                                    uri = repository.getPhotoUri(it.id).toString(),
                                    takenAtText = dateFormatter.format(Date(it.dateTaken)),
                                    isAnalyzed = repository.getAnalysisResult(it.id) != null
                                )
                            }
                        }

                        DuplicateGroup(
                            id = "photo_duplicates_$index",
                            photos = photos,
                            similarityScore = duplicate.similarity,
                            recommendedKeepId = duplicate.recommendKeep
                        )
                    },
                    privacyAlerts = plan.privacyRisks.map { alert ->
                        val photo = repository.getPhotoById(alert.photoId)

                        PrivacyAlert(
                            id = alert.photoId,
                            photo = PhotoUiModel(
                                id = alert.photoId,
                                uri = photo?.let { repository.getPhotoUri(it.id).toString() } ?: "",
                                takenAtText = photo?.let { dateFormatter.format(Date(it.dateTaken)) } ?: "",
                                hasPrivacyAlert = true,
                                isAnalyzed = true
                            ),
                            alertType = alert.privacyType,
                            description = alert.description,
                            severity = when {
                                alert.privacyType.contains("身份证") || alert.privacyType.contains("银行卡") -> PrivacySeverity.HIGH
                                alert.privacyType.contains("截图") -> PrivacySeverity.MEDIUM
                                else -> PrivacySeverity.LOW
                            }
                        )
                    }
                )

                _uiState.update {
                    it.copy(
                        plan = uiPlan,
                        isGeneratingPlan = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGeneratingPlan = false,
                        errorMessage = "生成整理方案失败: ${e.message}"
                    )
                }
            }
        }
    }

    private fun toggleSuggestion(suggestionId: String) {
        _uiState.update { currentState ->
            val newSelection = if (suggestionId in currentState.selectedSuggestionIds) {
                currentState.selectedSuggestionIds - suggestionId
            } else {
                currentState.selectedSuggestionIds + suggestionId
            }
            currentState.copy(selectedSuggestionIds = newSelection)
        }
    }

    private fun executeSelected() {
        // TODO: 实现执行整理逻辑
        viewModelScope.launch {
            // 显示预览、获取确认、然后执行
        }
    }

    private fun reviewDuplicateCleanup(event: OrganizeUiEvent.ReviewDuplicateCleanup) {
        val plan = organizationPlan ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isExecutingCleanup = true, errorMessage = null) }
            try {
                val review = organizeUseCase.reviewDuplicateCleanup(
                    plan = plan,
                    selectedCandidateIds = event.selectedCandidateIds,
                    keepOverrides = event.keepOverrides,
                    protectedPhotoIds = event.protectedPhotoIds
                )
                _uiState.update { it.copy(cleanupReview = review, isExecutingCleanup = false) }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isExecutingCleanup = false, errorMessage = "清理复核失败: ${error.message}")
                }
            }
        }
    }

    private fun requestReviewedDeletion() {
        if (_uiState.value.pendingDeleteRequest != null) return
        val review = _uiState.value.cleanupReview ?: return
        try {
            val request = organizeUseCase.requestDeleteAfterReview(review)
            _uiState.update { it.copy(pendingDeleteRequest = request, errorMessage = null) }
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = "无法创建删除请求: ${error.message}") }
        }
    }

    private fun confirmCleanupDeletion(event: OrganizeUiEvent.CleanupConfirmationResult) {
        if (_uiState.value.pendingDeleteRequest?.requestId != event.requestId) return
        viewModelScope.launch {
            _uiState.update { it.copy(isExecutingCleanup = true, pendingDeleteRequest = null) }
            try {
                val result = organizeUseCase.confirmCleanupDeletion(
                    requestId = event.requestId,
                    approved = event.approved,
                    deletionCompletedBySystem = event.systemDeleteCompleted
                )
                _uiState.update {
                    it.copy(
                        cleanupReview = if (result.cancelled) it.cleanupReview else null,
                        isExecutingCleanup = false,
                        errorMessage = result.failedIds.takeIf(List<String>::isNotEmpty)
                            ?.let { ids -> "${ids.size} 张照片删除失败" }
                    )
                }
                if (!result.cancelled && result.deletedIds.isNotEmpty()) loadOrganizationPlan()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isExecutingCleanup = false, errorMessage = "执行清理失败: ${error.message}")
                }
            }
        }
    }

    private fun dismissCleanupReview() {
        if (_uiState.value.pendingDeleteRequest != null) return
        _uiState.update { it.copy(cleanupReview = null, pendingDeleteRequest = null) }
    }

    private fun undoLastAction() {
        // TODO: 实现撤销逻辑
    }
}
