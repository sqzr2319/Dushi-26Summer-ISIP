package com.example.isip.ui.photo

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhotoDetailUiState(
    val photo: PhotoUiModel? = null,
    val ocrText: String? = null,
    val description: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed interface PhotoDetailUiEvent {
    data object StartAnalysis : PhotoDetailUiEvent
    data object Delete : PhotoDetailUiEvent
    data object CopyOcrText : PhotoDetailUiEvent
}

class PhotoDetailViewModel(application: Application) : AndroidViewModel(application) {

    // 初始化 Repository 和 UseCase
    private val repository = PhotoRepository.getInstance(application)
    private val analyzeUseCase = AnalyzePhotosUseCase(repository)

    private val _uiState = MutableStateFlow(PhotoDetailUiState())
    val uiState: StateFlow<PhotoDetailUiState> = _uiState.asStateFlow()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun loadPhoto(photoId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                // 从 Repository 加载照片
                val photo = repository.getPhotoById(photoId)
                if (photo == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "照片不存在"
                        )
                    }
                    return@launch
                }

                // 加载分析结果
                val analysisResult = repository.getAnalysisResult(photoId)

                val photoUiModel = PhotoUiModel(
                    id = photo.id,
                    uri = repository.getPhotoUri(photo.id).toString(),
                    takenAtText = dateFormatter.format(Date(photo.dateTaken)),
                    categories = analysisResult?.categories ?: emptyList(),
                    tags = analysisResult?.tags ?: emptyList(),
                    hasPrivacyAlert = false, // TODO: 从分析结果判断
                    isAnalyzed = analysisResult != null
                )

                _uiState.update {
                    it.copy(
                        photo = photoUiModel,
                        ocrText = analysisResult?.ocrText?.takeIf { text -> text.isNotBlank() },
                        description = analysisResult?.description,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "加载失败: ${e.message}"
                    )
                }
            }
        }
    }

    fun onEvent(event: PhotoDetailUiEvent) {
        when (event) {
            is PhotoDetailUiEvent.StartAnalysis -> startAnalysis()
            is PhotoDetailUiEvent.Delete -> deletePhoto()
            is PhotoDetailUiEvent.CopyOcrText -> copyOcrText()
        }
    }

    private fun startAnalysis() {
        val photoId = _uiState.value.photo?.id ?: return

        viewModelScope.launch {
            try {
                // 触发单张照片分析
                val result = analyzeUseCase.analyzeSinglePhoto(photoId)

                if (result != null) {
                    // 重新加载照片以显示分析结果
                    loadPhoto(photoId)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "分析失败: ${e.message}")
                }
            }
        }
    }

    private fun deletePhoto() {
        // TODO: 实现删除功能（需要确认对话框）
    }

    private fun copyOcrText() {
        // TODO: 复制 OCR 文本到剪贴板
    }
}
