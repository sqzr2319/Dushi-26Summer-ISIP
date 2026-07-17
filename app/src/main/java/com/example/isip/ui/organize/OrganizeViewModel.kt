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
import com.example.isip.domain.usecase.SmartAlbumUseCase
import com.example.isip.data.ai.MobileClipProvider
import com.example.isip.data.model.OrganizationPlan
import com.example.isip.domain.skill.CreateSmartAlbumSkill
import com.example.isip.domain.skill.GenerateStrategySkill
import com.example.isip.ui.model.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OrganizeUiState(
    val plan: OrganizationPlanUiModel? = null,
    val sourcePlan: OrganizationPlan? = null,
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
    private val organizeUseCase = OrganizePhotosUseCase(
        repository,
        GenerateStrategySkill(MobileClipProvider.getOrNull(application))
    )
    private val smartAlbumUseCase = SmartAlbumUseCase(repository)

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
                        sourcePlan = plan,
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
        viewModelScope.launch {
            val state = _uiState.value
            val plan = state.sourcePlan ?: return@launch
            val selectedAlbums = plan.albums.filter { it.id in state.selectedSuggestionIds }
            if (selectedAlbums.isEmpty()) return@launch

            try {
                val existingNames = smartAlbumUseCase.getAll().map { it.name }.toSet()
                selectedAlbums.filterNot { it.name in existingNames }.forEach { album ->
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
                        selectedSuggestionIds = emptySet(),
                        lastActionUndoable = false,
                        errorMessage = null
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "创建智能相册失败: ${error.message}")
                }
            }
        }
    }

    private fun undoLastAction() {
        // TODO: 实现撤销逻辑
    }
}
