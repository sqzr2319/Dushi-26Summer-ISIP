package com.example.isip.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("搜索") }) }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SearchInputBar(
                query = uiState.query,
                onQueryChange = { viewModel.onEvent(SearchUiEvent.UpdateQuery(it)) },
                onSearch = { viewModel.onEvent(SearchUiEvent.Search) },
                onVoiceInput = { viewModel.onEvent(SearchUiEvent.VoiceInput) }
            )

            if (uiState.query.isBlank()) {
                SearchSuggestionChips(
                    suggestions = uiState.suggestions,
                    onSuggestionClick = { suggestion ->
                        viewModel.onEvent(SearchUiEvent.UpdateQuery(suggestion))
                        viewModel.onEvent(SearchUiEvent.Search)
                    }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        uiState.isSearching -> InlineLoadingState("正在搜索…")
                        uiState.results.isEmpty() -> EmptyState(
                            icon = Icons.Default.SearchOff,
                            title = "没有找到照片",
                            description = "换一个关键词，或尝试搜索标签和图片中的文字。"
                        )
                        else -> Column(modifier = Modifier.fillMaxSize()) {
                            if (uiState.isReranking) {
                                InlineLoadingState("正在优化结果顺序…")
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
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
}
