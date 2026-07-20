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
import com.example.isip.data.ai.HybridPhotoContentAnalyzer
import com.example.isip.data.ai.MobileClipProvider
import com.example.isip.data.media.MediaStorePhotoDeletionGateway
import com.example.isip.domain.skill.DeletePhotoSkill
import com.example.isip.domain.skill.SummarizeSelectionSkill
import com.example.isip.domain.usecase.AnalyzePhotosUseCase
import com.example.isip.ui.model.PhotoUiModel
import com.example.isip.ui.model.AnalysisProgressUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    // 初始化 Repository 和 UseCase
    // 使用本地 Gemma 4；模型不可用时仅返回基础分类，不调用云端模型。
    private val repository = PhotoRepository.getInstance(application)
    private val mediaStoreDeletionGateway = MediaStorePhotoDeletionGateway(application, repository)
    private val deletePhotoSkill = DeletePhotoSkill(mediaStoreDeletionGateway)
    private val summarizeSelectionSkill = SummarizeSelectionSkill()
    private val analyzeUseCase = AnalyzePhotosUseCase(
        repository,
        HybridPhotoContentAnalyzer(application),
        MobileClipProvider.getOrNull(application)
    )

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var photosLoadInProgress = false

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
            is GalleryUiEvent.ShowSelectionSummary -> showSelectionSummary()
            is GalleryUiEvent.DismissSelectionSummary -> dismissSelectionSummary()
            is GalleryUiEvent.DeleteConfirmationResult -> confirmDelete(event)
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

    private fun loadPhotos(isRefresh: Boolean = false) {
        if (photosLoadInProgress) return
        photosLoadInProgress = true
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = !isRefresh,
                    isRefreshing = isRefresh,
                    errorMessage = null
                )
            }

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
                        isLoading = false,
                        isRefreshing = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = "加载照片失败: ${e.message}"
                    )
                }
            } finally {
                photosLoadInProgress = false
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
            loadPhotos(isRefresh = true)
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
            currentState.copy(selectedPhotoIds = newSelection, selectionSummary = null)
        }
    }

    private fun clearSelection() {
        _uiState.update { it.copy(selectedPhotoIds = emptySet(), selectionSummary = null) }
    }

    private fun showSelectionSummary() {
        val selectedIds = _uiState.value.selectedPhotoIds
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            try {
                val photos = selectedIds.mapNotNull { repository.getPhotoById(it) }
                val analyses = repository.getAllAnalysisResults().filter { it.photoId in selectedIds }
                val summary = summarizeSelectionSkill.execute(
                    SummarizeSelectionSkill.Input(photos = photos, analyses = analyses)
                )
                _uiState.update { it.copy(selectionSummary = summary, errorMessage = null) }
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = "生成选择摘要失败: ${error.message}") }
            }
        }
    }

    private fun dismissSelectionSummary() {
        _uiState.update { it.copy(selectionSummary = null) }
    }

    private fun deleteSelected() {
        val selectedIds = _uiState.value.selectedPhotoIds.toList()
        if (selectedIds.isEmpty()) return
        val request = deletePhotoSkill.requestDeleteAfterConfirmation(selectedIds)
        _uiState.update { it.copy(pendingDeleteRequest = request, errorMessage = null) }
    }

    private fun confirmDelete(event: GalleryUiEvent.DeleteConfirmationResult) {
        viewModelScope.launch {
            val request = _uiState.value.pendingDeleteRequest
            if (request?.requestId != event.requestId) return@launch
            _uiState.update { it.copy(pendingDeleteRequest = null) }
            try {
                val result = if (event.approved && event.systemDeleteCompleted) {
                    deletePhotoSkill.confirm(
                        DeletePhotoSkill.Confirmation(event.requestId, true),
                        deletionCompletedBySystem = true
                    )
                } else {
                    deletePhotoSkill.confirm(
                        DeletePhotoSkill.Confirmation(event.requestId, event.approved)
                    )
                }
                if (result.cancelled) return@launch
                _uiState.update { state ->
                    state.copy(
                        photos = state.photos.filterNot { it.id in result.deletedIds },
                        selectedPhotoIds = state.selectedPhotoIds - result.deletedIds.toSet(),
                        errorMessage = result.failedIds.takeIf(List<String>::isNotEmpty)
                            ?.let { "${it.size} 张照片删除失败，请重新授权后重试" }
                    )
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = "删除失败: ${error.message}") }
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
