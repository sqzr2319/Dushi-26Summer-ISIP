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
import com.example.isip.domain.usecase.OrganizePhotosUseCase
import com.example.isip.ui.model.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OrganizeUiState(
    val plan: OrganizationPlanUiModel? = null,
    val selectedSuggestionIds: Set<String> = emptySet(),
    val isGeneratingPlan: Boolean = false,
    val actionPreview: BatchActionPreviewUi? = null,
    val lastActionUndoable: Boolean = false,
    val errorMessage: String? = null
)

sealed interface OrganizeUiEvent {
    data object GeneratePlan : OrganizeUiEvent
    data class ToggleSuggestion(val suggestionId: String) : OrganizeUiEvent
    data object ExecuteSelected : OrganizeUiEvent
    data object UndoLastAction : OrganizeUiEvent
}

class OrganizeViewModel(application: Application) : AndroidViewModel(application) {

    // 初始化 Repository 和 UseCase
    private val repository = PhotoRepository.getInstance(application)
    private val organizeUseCase = OrganizePhotosUseCase(repository)

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
            is OrganizeUiEvent.UndoLastAction -> undoLastAction()
        }
    }

    private fun loadOrganizationPlan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingPlan = true, errorMessage = null) }

            try {
                // 调用真实的整理 UseCase
                val plan = organizeUseCase.generateOrganizationPlan()

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
                    duplicateGroups = plan.duplicates.map { duplicate ->
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
                            id = duplicate.photoIds.first(),
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

    private fun undoLastAction() {
        // TODO: 实现撤销逻辑
    }
}
