package com.example.isip.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.isip.ui.model.PhotoUiModel

class GalleryViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    fun onEvent(event: GalleryUiEvent) {
        when (event) {
            is GalleryUiEvent.RequestPermission -> requestPermission()
            is GalleryUiEvent.StartAnalysis -> startAnalysis()
            is GalleryUiEvent.PauseAnalysis -> pauseAnalysis()
            is GalleryUiEvent.ResumeAnalysis -> resumeAnalysis()
            is GalleryUiEvent.SelectCategory -> selectCategory(event.category)
            is GalleryUiEvent.TogglePhotoSelection -> togglePhotoSelection(event.photoId)
            is GalleryUiEvent.OpenPhoto -> {}
            is GalleryUiEvent.ClearSelection -> clearSelection()
            is GalleryUiEvent.DeleteSelected -> deleteSelected()
        }
    }

    private fun requestPermission() {
        // TODO: Implement permission request logic
        viewModelScope.launch {
            _uiState.update { it.copy(permissionGranted = true) }
            loadPhotos()
        }
    }

    private fun loadPhotos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // TODO: Load actual photos from repository
            // Simulating with mock data for now
            val mockPhotos = listOf(
                PhotoUiModel(
                    id = "1",
                    uri = "content://media/external/images/media/1",
                    takenAtText = "2024-07-10 10:30",
                    categories = listOf("风景"),
                    tags = listOf("海滩", "日落"),
                    isAnalyzed = true
                ),
                PhotoUiModel(
                    id = "2",
                    uri = "content://media/external/images/media/2",
                    takenAtText = "2024-07-09 15:20",
                    categories = listOf("人物"),
                    tags = listOf("家人", "聚会"),
                    isAnalyzed = true
                ),
                PhotoUiModel(
                    id = "3",
                    uri = "content://media/external/images/media/3",
                    takenAtText = "2024-07-08 09:15",
                    categories = listOf("截图"),
                    tags = listOf("聊天记录"),
                    hasPrivacyAlert = true,
                    isAnalyzed = true
                )
            )

            _uiState.update {
                it.copy(
                    photos = mockPhotos,
                    isLoading = false
                )
            }
        }
    }

    private fun startAnalysis() {
        // TODO: Start analysis task
    }

    private fun pauseAnalysis() {
        // TODO: Pause analysis task
    }

    private fun resumeAnalysis() {
        // TODO: Resume analysis task
    }

    private fun selectCategory(category: String) {
        _uiState.update { it.copy(activeCategory = category) }
    }

    private fun togglePhotoSelection(photoId: String) {
        _uiState.update { currentState ->
            val newSelection = if (photoId in currentState.selectedPhotoIds) {
                currentState.selectedPhotoIds - photoId
            } else {
                currentState.selectedPhotoIds + photoId
            }
            currentState.copy(selectedPhotoIds = newSelection)
        }
    }

    private fun clearSelection() {
        _uiState.update { it.copy(selectedPhotoIds = emptySet()) }
    }

    private fun deleteSelected() {
        // TODO: Implement delete logic with confirmation
        viewModelScope.launch {
            val selectedIds = _uiState.value.selectedPhotoIds
            _uiState.update {
                it.copy(
                    photos = it.photos.filterNot { photo -> photo.id in selectedIds },
                    selectedPhotoIds = emptySet()
                )
            }
        }
    }

    fun getFilteredPhotos(): List<PhotoUiModel> {
        val state = _uiState.value
        return when (state.activeCategory) {
            "全部" -> state.photos
            else -> state.photos.filter { state.activeCategory in it.categories }
        }
    }
}
