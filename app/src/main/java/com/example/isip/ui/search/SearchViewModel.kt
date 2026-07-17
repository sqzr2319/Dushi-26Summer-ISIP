package com.example.isip.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.isip.data.PhotoRepository
import com.example.isip.domain.usecase.SearchPhotosUseCase
import com.example.isip.data.ai.MobileClipProvider
import com.example.isip.domain.skill.SemanticSearchSkill
import com.example.isip.ui.model.SearchResultUiModel
import com.example.isip.ui.model.PhotoUiModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val results: List<SearchResultUiModel> = emptyList(),
    val isSearching: Boolean = false,
    val isReranking: Boolean = false,
    val errorMessage: String? = null
)

sealed interface SearchUiEvent {
    data class UpdateQuery(val query: String) : SearchUiEvent
    data object Search : SearchUiEvent
    data object VoiceInput : SearchUiEvent
    data object Clear : SearchUiEvent
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    // 初始化 Repository 和 UseCase
    private val repository = PhotoRepository.getInstance(application)
    private val clipEngine = MobileClipProvider.getOrNull(application)
    private val searchUseCase = SearchPhotosUseCase(
        repository,
        semanticSearchSkill = SemanticSearchSkill(clipSearchEngine = clipEngine)
    )

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    init {
        loadSuggestions()
    }

    fun onEvent(event: SearchUiEvent) {
        when (event) {
            is SearchUiEvent.UpdateQuery -> updateQuery(event.query)
            is SearchUiEvent.Search -> performSearch()
            is SearchUiEvent.VoiceInput -> startVoiceInput()
            is SearchUiEvent.Clear -> clearSearch()
        }
    }

    private fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    private fun loadSuggestions() {
        viewModelScope.launch {
            try {
                val suggestions = searchUseCase.getSearchSuggestions()
                _uiState.update { it.copy(suggestions = suggestions) }
            } catch (e: Exception) {
                // 使用默认建议
                _uiState.update {
                    it.copy(
                        suggestions = listOf(
                            "今年的照片",
                            "旅行照片",
                            "截图",
                            "风景",
                            "人物"
                        )
                    )
                }
            }
        }
    }

    private fun performSearch() {
        val query = _uiState.value.query
        if (query.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, errorMessage = null) }

            try {
                // 调用真实的搜索 UseCase
                val searchResult = searchUseCase.search(query)

                // 转换为 UI 模型
                val uiResults = searchResult.results.map { item ->
                    val photo = repository.getPhotoById(item.photoId)
                    val analysisResult = repository.getAnalysisResult(item.photoId)

                    SearchResultUiModel(
                        photo = PhotoUiModel(
                            id = item.photoId,
                            uri = photo?.let { repository.getPhotoUri(it.id).toString() } ?: "",
                            takenAtText = photo?.let { dateFormatter.format(Date(it.dateTaken)) } ?: "",
                            categories = analysisResult?.categories ?: emptyList(),
                            tags = analysisResult?.tags ?: emptyList(),
                            isAnalyzed = analysisResult != null
                        ),
                        relevanceScoreText = "${(item.relevanceScore * 100).toInt()}%",
                        matchedTags = item.matchedTags
                    )
                }

                _uiState.update {
                    it.copy(
                        results = uiResults,
                        isSearching = false,
                        isReranking = uiResults.isNotEmpty() // 模拟重排序
                    )
                }

                // 模拟重排序完成
                if (uiResults.isNotEmpty()) {
                    kotlinx.coroutines.delay(500)
                    _uiState.update { it.copy(isReranking = false) }
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        isReranking = false,
                        errorMessage = "搜索失败: ${e.message}"
                    )
                }
            }
        }
    }

    private fun startVoiceInput() {
        // TODO: 实现语音输入（需要集成语音识别服务）
    }

    private fun clearSearch() {
        _uiState.update { it.copy(query = "", results = emptyList(), errorMessage = null) }
    }
}
