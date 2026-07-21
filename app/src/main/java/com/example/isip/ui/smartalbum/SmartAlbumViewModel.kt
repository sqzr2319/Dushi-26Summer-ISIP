package com.example.isip.ui.smartalbum

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.isip.data.PhotoRepository
import com.example.isip.domain.usecase.SmartAlbumUseCase
import com.example.isip.ui.model.PhotoUiModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SmartAlbumDetailUiState(
    val name: String = "智能相册",
    val ruleDescription: String = "",
    val photos: List<PhotoUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class SmartAlbumViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PhotoRepository.getInstance(application)
    private val smartAlbumUseCase = SmartAlbumUseCase(repository)
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val _uiState = MutableStateFlow(SmartAlbumDetailUiState())
    val uiState: StateFlow<SmartAlbumDetailUiState> = _uiState.asStateFlow()

    fun load(albumId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val album = smartAlbumUseCase.getAll().firstOrNull { it.id == albumId }
                    ?: error("智能相册不存在或已被删除")
                val analyses = repository.getAllAnalysisResults().associateBy { it.photoId }
                val photos = smartAlbumUseCase.resolvePhotos(album).map { photo ->
                    val analysis = analyses[photo.id]
                    PhotoUiModel(
                        id = photo.id,
                        uri = repository.getPhotoUri(photo.id).toString(),
                        takenAtText = dateFormatter.format(Date(photo.dateTaken)),
                        categories = analysis?.categories.orEmpty(),
                        tags = analysis?.tags.orEmpty(),
                        isAnalyzed = analysis != null
                    )
                }
                val ruleDescription = buildList {
                    if (album.rule.categories.isNotEmpty()) add("分类：${album.rule.categories.joinToString("、")}")
                    if (album.rule.tags.isNotEmpty()) add("标签：${album.rule.tags.joinToString("、")}")
                    if (album.rule.photoIds.isNotEmpty()) add("包含创建时选中的照片")
                }.joinToString(" · ").ifBlank { "自定义匹配规则" }
                _uiState.value = SmartAlbumDetailUiState(
                    name = album.name,
                    ruleDescription = ruleDescription,
                    photos = photos,
                    isLoading = false
                )
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = error.message ?: "加载智能相册失败")
                }
            }
        }
    }
}
