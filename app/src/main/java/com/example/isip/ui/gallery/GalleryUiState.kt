package com.example.isip.ui.gallery

import com.example.isip.ui.model.PhotoUiModel
import com.example.isip.ui.model.AnalysisProgressUi
import com.example.isip.ui.model.SmartAlbumUiModel
import com.example.isip.domain.skill.DeletePhotoSkill
import com.example.isip.domain.skill.SummarizeSelectionSkill

data class GalleryUiState(
    val permissionGranted: Boolean = false,
    val photos: List<PhotoUiModel> = emptyList(),
    val smartAlbums: List<SmartAlbumUiModel> = emptyList(),
    val selectedPhotoIds: Set<String> = emptySet(),
    val activeCategory: String = "全部",
    val analysisProgress: AnalysisProgressUi? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val pendingDeleteRequest: DeletePhotoSkill.DeleteRequest? = null,
    val selectionSummary: SummarizeSelectionSkill.SelectionSummary? = null
)

sealed interface GalleryUiEvent {
    data class PermissionResult(val granted: Boolean) : GalleryUiEvent
    data object StartAnalysis : GalleryUiEvent
    data object PauseAnalysis : GalleryUiEvent
    data object ResumeAnalysis : GalleryUiEvent
    data class SelectCategory(val category: String) : GalleryUiEvent
    data class TogglePhotoSelection(val photoId: String) : GalleryUiEvent
    data class OpenPhoto(val photoId: String) : GalleryUiEvent
    data object ClearSelection : GalleryUiEvent
    data object DeleteSelected : GalleryUiEvent
    data object ShowSelectionSummary : GalleryUiEvent
    data object DismissSelectionSummary : GalleryUiEvent
    data class DeleteConfirmationResult(
        val requestId: String,
        val approved: Boolean,
        val systemDeleteCompleted: Boolean
    ) : GalleryUiEvent
}
