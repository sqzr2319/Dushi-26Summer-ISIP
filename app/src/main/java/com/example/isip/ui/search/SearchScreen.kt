package com.example.isip.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.isip.ui.common.EmptyState
import com.example.isip.ui.common.InlineLoadingState
import com.example.isip.ui.gallery.PhotoGridItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onPhotoClick: (String) -> Unit,
    viewModel: SearchViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("搜索") }
        )

        SearchInputBar(
            query = uiState.query,
            onQueryChange = { viewModel.onEvent(SearchUiEvent.UpdateQuery(it)) },
            onSearch = { viewModel.onEvent(SearchUiEvent.Search) },
            onVoiceInput = { viewModel.onEvent(SearchUiEvent.VoiceInput) }
        )

        if (uiState.query.isEmpty()) {
            // Show suggestions
            SearchSuggestionChips(
                suggestions = uiState.suggestions,
                onSuggestionClick = { suggestion ->
                    viewModel.onEvent(SearchUiEvent.UpdateQuery(suggestion))
                    viewModel.onEvent(SearchUiEvent.Search)
                }
            )
        } else {
            // Show results
            when {
                uiState.isSearching -> {
                    InlineLoadingState(message = "搜索中...")
                }

                uiState.results.isEmpty() && !uiState.isSearching -> {
                    EmptyState(
                        icon = Icons.Default.SearchOff,
                        title = "无搜索结果",
                        description = "未找到符合条件的照片，请尝试其他关键词"
                    )
                }

                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (uiState.isReranking) {
                            InlineLoadingState(message = "正在优化排序...")
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(uiState.results, key = { it.photo.id }) { result ->
                                PhotoGridItem(
                                    photo = result.photo,
                                    isSelected = false,
                                    onClick = { onPhotoClick(result.photo.id) },
                                    onLongClick = {}
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
