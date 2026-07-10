package com.example.isip.ui.photo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.isip.ui.model.PhotoUiModel

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

class PhotoDetailViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PhotoDetailUiState())
    val uiState: StateFlow<PhotoDetailUiState> = _uiState.asStateFlow()

    fun loadPhoto(photoId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // TODO: Load actual photo from repository
            // Simulating with mock data
            kotlinx.coroutines.delay(300)

            val mockPhoto = PhotoUiModel(
                id = photoId,
                uri = "content://media/external/images/media/$photoId",
                takenAtText = "2024-07-10 15:30:25",
                categories = listOf("风景", "旅行"),
                tags = listOf("海滩", "日落", "沙滩", "大海"),
                hasPrivacyAlert = false,
                isAnalyzed = true
            )

            val mockOcrText = if (photoId == "3") {
                "这是一段从照片中识别出的文字内容示例。可能包含聊天记录、文档或其他文本信息。"
            } else null

            val mockDescription = "一张美丽的海滩日落照片，画面中可以看到金色的阳光洒在海面上，沙滩上有几个人的剪影。"

            _uiState.update {
                it.copy(
                    photo = mockPhoto,
                    ocrText = mockOcrText,
                    description = mockDescription,
                    isLoading = false
                )
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
        // TODO: Trigger analysis for this photo
    }

    private fun deletePhoto() {
        // TODO: Implement delete with confirmation
    }

    private fun copyOcrText() {
        // TODO: Copy OCR text to clipboard
    }
}
