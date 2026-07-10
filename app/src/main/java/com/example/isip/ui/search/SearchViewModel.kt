package com.example.isip.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.isip.ui.model.SearchResultUiModel
import com.example.isip.ui.model.PhotoUiModel

data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = listOf(
        "上周的截图",
        "海边的照片",
        "有发票的照片",
        "家人合照",
        "风景照片"
    ),
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

class SearchViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

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

    private fun performSearch() {
        val query = _uiState.value.query
        if (query.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }

            // TODO: Call actual search use case
            // Simulating search with mock data
            kotlinx.coroutines.delay(500)

            val mockResults = listOf(
                SearchResultUiModel(
                    photo = PhotoUiModel(
                        id = "search_1",
                        uri = "content://media/external/images/media/10",
                        takenAtText = "2024-07-05 14:30",
                        categories = listOf("风景"),
                        tags = listOf("海滩", "日落", "沙滩"),
                        isAnalyzed = true
                    ),
                    relevanceScoreText = "95%",
                    matchedTags = listOf("海滩", "日落")
                ),
                SearchResultUiModel(
                    photo = PhotoUiModel(
                        id = "search_2",
                        uri = "content://media/external/images/media/11",
                        takenAtText = "2024-07-04 16:20",
                        categories = listOf("风景"),
                        tags = listOf("海边", "礁石"),
                        isAnalyzed = true
                    ),
                    relevanceScoreText = "88%",
                    matchedTags = listOf("海边")
                )
            )

            _uiState.update {
                it.copy(
                    results = mockResults,
                    isSearching = false,
                    isReranking = true
                )
            }

            // Simulate reranking
            kotlinx.coroutines.delay(800)
            _uiState.update { it.copy(isReranking = false) }
        }
    }

    private fun startVoiceInput() {
        // TODO: Implement voice input
    }

    private fun clearSearch() {
        _uiState.update { it.copy(query = "", results = emptyList()) }
    }
}
