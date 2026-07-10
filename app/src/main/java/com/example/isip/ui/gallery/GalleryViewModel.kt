package com.example.isip.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.isip.data.PhotoRepository
import com.example.isip.domain.usecase.AnalyzePhotosUseCase
import com.example.isip.ui.model.PhotoUiModel
import com.example.isip.ui.model.AnalysisProgressUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    // 初始化 Repository 和 UseCase
    private val repository = PhotoRepository.getInstance(application)
    private val analyzeUseCase = AnalyzePhotosUseCase(repository)

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

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
        // 权限已授予后加载照片
        viewModelScope.launch {
            _uiState.update { it.copy(permissionGranted = true) }
            loadPhotos()
        }
    }

    private fun loadPhotos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                // 从 Repository 加载真实照片
                val photos = repository.getAllPhotos()

                // 转换为 UI 模型
                val photoUiModels = photos.map { photo ->
                    val analysisResult = repository.getAnalysisResult(photo.id)

                    PhotoUiModel(
                        id = photo.id,
                        uri = repository.getPhotoUri(photo.id).toString(),
                        takenAtText = dateFormatter.format(Date(photo.dateTaken)),
                        categories = analysisResult?.categories ?: emptyList(),
                        tags = analysisResult?.tags ?: emptyList(),
                        hasPrivacyAlert = false, // TODO: 从分析结果判断
                        isAnalyzed = analysisResult != null
                    )
                }

                _uiState.update {
                    it.copy(
                        photos = photoUiModels,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "加载照片失败: ${e.message}"
                    )
                }
            }
        }
    }

    private fun startAnalysis() {
        viewModelScope.launch {
            try {
                analyzeUseCase.analyzeAllPhotos()
                    .collect { progress ->
                        // 更新分析进度
                        _uiState.update {
                            it.copy(
                                analysisProgress = AnalysisProgressUi(
                                    total = progress.total,
                                    completed = progress.completed,
                                    currentTaskText = progress.message,
                                    progress = progress.progressPercent(),
                                    canPause = true,
                                    canCancel = true
                                )
                            )
                        }

                        // 分析完成后清除进度并重新加载照片
                        if (progress.completed >= progress.total) {
                            _uiState.update { it.copy(analysisProgress = null) }
                            loadPhotos()
                        }
                    }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        analysisProgress = null,
                        errorMessage = "分析失败: ${e.message}"
                    )
                }
            }
        }
    }

    private fun pauseAnalysis() {
        // TODO: 实现暂停逻辑
        _uiState.update {
            it.copy(
                analysisProgress = it.analysisProgress?.copy(canPause = false)
            )
        }
    }

    private fun resumeAnalysis() {
        // TODO: 实现恢复逻辑
        _uiState.update {
            it.copy(
                analysisProgress = it.analysisProgress?.copy(canPause = true)
            )
        }
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
        viewModelScope.launch {
            val selectedIds = _uiState.value.selectedPhotoIds
            // TODO: 实际删除照片文件
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
