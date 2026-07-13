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
import com.example.isip.data.ai.Qwen35PhotoContentAnalyzer
import com.example.isip.data.ai.MobileClipProvider
import com.example.isip.domain.usecase.AnalyzePhotosUseCase
import com.example.isip.ui.model.PhotoUiModel
import com.example.isip.ui.model.AnalysisProgressUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    // 初始化 Repository 和 UseCase
    private val repository = PhotoRepository.getInstance(application)
    private val analyzeUseCase = AnalyzePhotosUseCase(
        repository,
        Qwen35PhotoContentAnalyzer(application),
        MobileClipProvider.getOrNull(application)
    )

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun onEvent(event: GalleryUiEvent) {
        when (event) {
            is GalleryUiEvent.PermissionResult -> updatePermission(event.granted)
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

    private fun updatePermission(granted: Boolean) {
        val changed = _uiState.value.permissionGranted != granted
        _uiState.update {
            it.copy(
                permissionGranted = granted,
                errorMessage = if (granted) null else it.errorMessage
            )
        }
        if (granted && (changed || _uiState.value.photos.isEmpty())) loadPhotos()
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

    /**
     * 在从照片详情返回时重新读取分析状态。
     *
     * GalleryViewModel 会随导航返回复用，若不刷新，网格会一直保留分析前的
     * PhotoUiModel，从而错误显示为“未分析”。
     */
    fun refreshPhotos() {
        if (_uiState.value.permissionGranted) {
            loadPhotos()
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
