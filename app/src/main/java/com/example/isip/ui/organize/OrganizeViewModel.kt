package com.example.isip.ui.organize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.isip.ui.model.*

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

class OrganizeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(OrganizeUiState())
    val uiState: StateFlow<OrganizeUiState> = _uiState.asStateFlow()

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
            _uiState.update { it.copy(isGeneratingPlan = true) }

            // TODO: Load actual plan from use case
            // Simulating with mock data
            kotlinx.coroutines.delay(1000)

            val mockPlan = OrganizationPlanUiModel(
                categorySuggestions = listOf(
                    CategorySuggestion(
                        id = "cat_1",
                        categoryName = "2024年7月旅行",
                        photoCount = 45,
                        description = "包含海滩、酒店和美食照片"
                    ),
                    CategorySuggestion(
                        id = "cat_2",
                        categoryName = "工作截图",
                        photoCount = 23,
                        description = "聊天记录和工作文档截图"
                    )
                ),
                duplicateGroups = listOf(
                    DuplicateGroup(
                        id = "dup_1",
                        photos = listOf(
                            PhotoUiModel(
                                id = "dup_photo_1",
                                uri = "content://media/1",
                                takenAtText = "2024-07-10 10:30",
                                isAnalyzed = true
                            ),
                            PhotoUiModel(
                                id = "dup_photo_2",
                                uri = "content://media/2",
                                takenAtText = "2024-07-10 10:30",
                                isAnalyzed = true
                            )
                        ),
                        similarityScore = 0.98f,
                        recommendedKeepId = "dup_photo_1"
                    )
                ),
                privacyAlerts = listOf(
                    PrivacyAlert(
                        id = "priv_1",
                        photo = PhotoUiModel(
                            id = "priv_photo_1",
                            uri = "content://media/10",
                            takenAtText = "2024-07-05",
                            hasPrivacyAlert = true,
                            isAnalyzed = true
                        ),
                        alertType = "身份证",
                        description = "检测到身份证信息",
                        severity = PrivacySeverity.HIGH
                    )
                )
            )

            _uiState.update {
                it.copy(
                    plan = mockPlan,
                    isGeneratingPlan = false
                )
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
        // TODO: Implement execution logic
        viewModelScope.launch {
            // Show preview, get confirmation, then execute
        }
    }

    private fun undoLastAction() {
        // TODO: Implement undo logic
    }
}
