package com.example.isip.ui.gallery

import com.example.isip.ui.model.PhotoUiModel
import com.example.isip.ui.model.AnalysisProgressUi

data class GalleryUiState(
    val permissionGranted: Boolean = false,
    val photos: List<PhotoUiModel> = emptyList(),
    val selectedPhotoIds: Set<String> = emptySet(),
    val activeCategory: String = "全部",
    val analysisProgress: AnalysisProgressUi? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed interface GalleryUiEvent {
    data object RequestPermission : GalleryUiEvent
    data object StartAnalysis : GalleryUiEvent
    data object PauseAnalysis : GalleryUiEvent
    data object ResumeAnalysis : GalleryUiEvent
    data class SelectCategory(val category: String) : GalleryUiEvent
    data class TogglePhotoSelection(val photoId: String) : GalleryUiEvent
    data class OpenPhoto(val photoId: String) : GalleryUiEvent
    data object ClearSelection : GalleryUiEvent
    data object DeleteSelected : GalleryUiEvent
}
